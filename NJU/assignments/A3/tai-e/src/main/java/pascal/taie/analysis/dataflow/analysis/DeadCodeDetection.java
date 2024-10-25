/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.dataflow.analysis;

import pascal.taie.analysis.MethodAnalysis;
import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.analysis.constprop.ConstantPropagation;
import pascal.taie.analysis.dataflow.analysis.constprop.Value;
import pascal.taie.analysis.dataflow.fact.DataflowResult;
import pascal.taie.analysis.dataflow.fact.SetFact;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.cfg.Edge;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.stmt.AssignStmt;
import pascal.taie.ir.stmt.If;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.SwitchStmt;

import java.util.*;

public class DeadCodeDetection extends MethodAnalysis {

    public static final String ID = "deadcode";

    public DeadCodeDetection(AnalysisConfig config) {
        super(config);
    }

    @Override
    public Set<Stmt> analyze(IR ir) {
        // obtain CFG
        CFG<Stmt> cfg = ir.getResult(CFGBuilder.ID);
        // obtain result of constant propagation
        DataflowResult<Stmt, CPFact> constants = ir.getResult(ConstantPropagation.ID);
        // obtain result of live variable analysis
        DataflowResult<Stmt, SetFact<Var>> liveVars = ir.getResult(LiveVariableAnalysis.ID);
        // keep statements (dead code) sorted in the resulting set
        Set<Stmt> deadCode = new TreeSet<>(Comparator.comparing(Stmt::getIndex));

        // add unreachable branch into dead code
        deadCode.addAll(analyzeUnreachableBranch(cfg, constants));

        // add control flow unreachable statements into dead code
        analyzeControlFlowUnreachable(cfg, deadCode);

        // add dead assignment into dead code
        deadCode.addAll(analyzeDeadAssign(cfg, liveVars));

        // Your task is to recognize dead code in ir and add it to deadCode
        return deadCode;
    }

    /**
     * @return dead cade that can not be reached in control flow
     */
    private void analyzeControlFlowUnreachable(CFG<Stmt> cfg, Set<Stmt> deadCode) {
        // enumerate each node, color reachable node
        Set<Stmt> visited = new HashSet<>();
        ArrayList<Stmt> list = new ArrayList<>();
        list.add(cfg.getEntry());
        while (!list.isEmpty()) {
            Stmt cur = list.remove(0);
            if (visited.contains(cur) || deadCode.contains(cur)) {
                continue;
            }
            visited.add(cur);
            cfg.getOutEdgesOf(cur).forEach(edge -> list.add(edge.getTarget()));
        }

        for (Stmt node : cfg.getNodes()) {
            if (!visited.contains(node) && !cfg.isExit(node)) {
                deadCode.add(node);
            }
        }
    }

    /**
     * @return dead code that can not be reached during branch
     */
    private Set<Stmt> analyzeUnreachableBranch(CFG<Stmt> cfg, DataflowResult<Stmt, CPFact> constants) {
        Set<Stmt> deadCode = new TreeSet<>(Comparator.comparing(Stmt::getIndex));
        for (Stmt node : cfg.getNodes()) {
            // resolve if statement
            if (node instanceof If ifStmt) {
                Value condExpResult = ConstantPropagation.evaluate(ifStmt.getCondition(), constants.getInFact(ifStmt));
                if (condExpResult.isConstant()) {
                    Edge.Kind targetEdge = (condExpResult.getConstant() != 0) ? Edge.Kind.IF_FALSE : Edge.Kind.IF_TRUE;

                    cfg.getOutEdgesOf(ifStmt).stream()
                            .filter(edge -> edge.getKind() == targetEdge)
                            .map(Edge::getTarget)
                            .forEach(deadCode::add);
                }
            }
            // resolve switch case statement
            if (node instanceof SwitchStmt switchStmt) {
                Value condExpResult = ConstantPropagation.evaluate(switchStmt.getVar(),
                        constants.getInFact(switchStmt));
                if (condExpResult.isConstant()) {
                    int switchValue = condExpResult.getConstant();
                    Set<Edge<Stmt>> outEdges = cfg.getOutEdgesOf(switchStmt);
                    boolean hasMatchingCase = outEdges.stream()
                            .filter(Edge::isSwitchCase)
                            .anyMatch(edge -> switchValue == edge.getCaseValue());
                    outEdges.stream()
                            .filter(edge -> (edge.isSwitchCase() && switchValue != edge.getCaseValue()) ||
                                    (hasMatchingCase && edge.getKind() == Edge.Kind.SWITCH_DEFAULT))
                            .map(Edge::getTarget)
                            .forEach(deadCode::add);
                }
            }
        }
        return deadCode;
    }

    private Set<Stmt> analyzeDeadAssign(CFG<Stmt> cfg, DataflowResult<Stmt, SetFact<Var>> liveVars) {
        Set<Stmt> deadCode = new TreeSet<>(Comparator.comparing(Stmt::getIndex));
        for (Stmt node : cfg.getNodes()) {
            if (node instanceof AssignStmt<?, ?> assignStmt) {
                LValue lvalue = assignStmt.getLValue();
                if (lvalue instanceof Var var && hasNoSideEffect(assignStmt.getRValue())) {
                    if (!liveVars.getResult(node).contains(var)) {
                        deadCode.add(node);
                    }
                }
            }
        }
        return deadCode;
    }

    /**
     * @return true if given RValue has no side effect, otherwise false.
     */
    private static boolean hasNoSideEffect(RValue rvalue) {
        // new expression modifies the heap
        if (rvalue instanceof NewExp ||
        // cast may trigger ClassCastException
                rvalue instanceof CastExp ||
                // static field access may trigger class initialization
                // instance field access may trigger NPE
                rvalue instanceof FieldAccess ||
                // array access may trigger NPE
                rvalue instanceof ArrayAccess) {
            return false;
        }
        if (rvalue instanceof ArithmeticExp) {
            ArithmeticExp.Op op = ((ArithmeticExp) rvalue).getOperator();
            // may trigger DivideByZeroException
            return op != ArithmeticExp.Op.DIV && op != ArithmeticExp.Op.REM;
        }
        return true;
    }
}
