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

        // add unreachable statements into dead code
        analyzeControlFlowUnreachable(cfg, deadCode, constants);

        // add dead assignment into dead code
        deadCode.addAll(analyzeDeadAssign(cfg, liveVars));

        deadCode.remove(cfg.getExit());
        deadCode.remove(cfg.getEntry());

        // Your task is to recognize dead code in ir and add it to deadCode
        return deadCode;
    }

    /**
     * Analyzes the control flow graph to identify unreachable code blocks.
     * Uses constant propagation results to determine which branches of conditional
     * statements
     * are actually reachable during runtime.
     *
     * @param cfg       The control flow graph to analyze
     * @param deadCode  Set to store identified dead code statements
     * @param constants Results from constant propagation analysis
     */
    private void analyzeControlFlowUnreachable(CFG<Stmt> cfg, Set<Stmt> deadCode,
            DataflowResult<Stmt, CPFact> constants) {
        Set<Stmt> visited = new HashSet<>();
        Queue<Stmt> queue = new LinkedList<>();
        queue.offer(cfg.getEntry());

        while (!queue.isEmpty()) {
            Stmt cur = queue.poll();
            if (!visited.add(cur)) {
                continue;
            }

            getReachableSuccessors(cfg, cur, constants)
                    .forEach(queue::offer);
        }

        markUnreachableNodes(cfg, visited, deadCode);
    }

    /**
     * Determines the reachable successor statements for a given statement.
     * Handles different types of control flow statements (if/switch) differently
     * based on constant propagation results.
     *
     * @param cfg       The control flow graph
     * @param stmt      The current statement to analyze
     * @param constants Results from constant propagation analysis
     * @return Collection of reachable successor statements
     */
    private Collection<Stmt> getReachableSuccessors(CFG<Stmt> cfg, Stmt stmt,
            DataflowResult<Stmt, CPFact> constants) {
        if (stmt instanceof If ifStmt) {
            return handleIfStatement(cfg, ifStmt, constants);
        }
        if (stmt instanceof SwitchStmt switchStmt) {
            return handleSwitchStatement(cfg, switchStmt, constants);
        }
        return getDefaultSuccessors(cfg, stmt);
    }

    /**
     * Analyzes an if statement to determine which branches are reachable.
     * If the condition can be evaluated to a constant, only returns the relevant
     * branch.
     *
     * @param cfg       The control flow graph
     * @param ifStmt    The if statement to analyze
     * @param constants Results from constant propagation analysis
     * @return Collection of reachable successor statements
     */
    private Collection<Stmt> handleIfStatement(CFG<Stmt> cfg, If ifStmt,
            DataflowResult<Stmt, CPFact> constants) {
        Value condValue = ConstantPropagation.evaluate(
                ifStmt.getCondition(),
                constants.getResult(ifStmt));

        if (!condValue.isConstant()) {
            return getDefaultSuccessors(cfg, ifStmt);
        }

        Edge.Kind reachableKind = (condValue.getConstant() != 0) ? Edge.Kind.IF_TRUE : Edge.Kind.IF_FALSE;

        return cfg.getOutEdgesOf(ifStmt).stream()
                .filter(edge -> edge.getKind() == reachableKind)
                .map(Edge::getTarget)
                .toList();
    }

    /**
     * Analyzes a switch statement to determine which cases are reachable.
     * If the switch value is constant, returns only the matching case or default.
     *
     * @param cfg        The control flow graph
     * @param switchStmt The switch statement to analyze
     * @param constants  Results from constant propagation analysis
     * @return Collection of reachable successor statements
     */
    private Collection<Stmt> handleSwitchStatement(CFG<Stmt> cfg, SwitchStmt switchStmt,
            DataflowResult<Stmt, CPFact> constants) {
        Value switchValue = ConstantPropagation.evaluate(
                switchStmt.getVar(),
                constants.getResult(switchStmt));

        if (!switchValue.isConstant()) {
            return getDefaultSuccessors(cfg, switchStmt);
        }

        int value = switchValue.getConstant();
        var matchingCase = cfg.getOutEdgesOf(switchStmt).stream()
                .filter(edge -> edge.isSwitchCase() && edge.getCaseValue() == value)
                .findFirst();

        return matchingCase.<Collection<Stmt>>map(stmtEdge -> List.of(stmtEdge.getTarget()))
                .orElseGet(() -> cfg.getOutEdgesOf(switchStmt).stream()
                        .filter(edge -> edge.getKind() == Edge.Kind.SWITCH_DEFAULT)
                        .map(Edge::getTarget)
                        .toList());

    }

    /**
     * Returns all immediate successors for a given statement.
     * Used for non-conditional statements or when conditional values cannot be
     * determined.
     *
     * @param cfg  The control flow graph
     * @param stmt The statement to get successors for
     * @return Collection of all successor statements
     */
    private Collection<Stmt> getDefaultSuccessors(CFG<Stmt> cfg, Stmt stmt) {
        return cfg.getOutEdgesOf(stmt).stream()
                .map(Edge::getTarget)
                .toList();
    }

    /**
     * Marks all unvisited nodes in the CFG as unreachable (dead code).
     *
     * @param cfg      The control flow graph
     * @param visited  Set of statements that were reached during analysis
     * @param deadCode Set to store identified dead code statements
     */
    private void markUnreachableNodes(CFG<Stmt> cfg, Set<Stmt> visited, Set<Stmt> deadCode) {
        cfg.getNodes().stream()
                .filter(node -> !visited.contains(node))
                .forEach(deadCode::add);
    }

    /**
     * Analyzes the program to identify dead assignments (assignments to variables
     * that are never used).
     * Uses live variable analysis results to determine if assigned variables are
     * used later.
     *
     * @param cfg      The control flow graph
     * @param liveVars Results from live variable analysis
     * @return Sorted set of statements containing dead assignments
     */
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
