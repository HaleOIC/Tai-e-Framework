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

package pascal.taie.analysis.pta.ci;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.DefaultCallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.AnalysisException;
import pascal.taie.language.type.Type;
import polyglot.ast.Assign;
import polyglot.ast.Call;

import java.util.List;

class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final HeapModel heapModel;

    private DefaultCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private StmtProcessor stmtProcessor;

    private ClassHierarchy hierarchy;

    Solver(HeapModel heapModel) {
        this.heapModel = heapModel;
    }

    /**
     * Runs pointer analysis algorithm.
     */
    void solve() {
        initialize();
        analyze();
    }

    /**
     * Initializes pointer analysis.
     */
    private void initialize() {
        workList = new WorkList();
        pointerFlowGraph = new PointerFlowGraph();
        callGraph = new DefaultCallGraph();
        stmtProcessor = new StmtProcessor();
        hierarchy = World.get().getClassHierarchy();
        // initialize main method
        JMethod main = World.get().getMainMethod();
        callGraph.addEntryMethod(main);
        addReachable(main);
    }

    /**
     * Processes new reachable method.
     */
    private void addReachable(JMethod method) {
        if (callGraph.contains(method)) {
            return;
        }
        callGraph.addEntryMethod(method);
        for (Stmt stmt : method.getIR().getStmts()) {
            stmt.accept(stmtProcessor);
        }
    }

    /**
     * Processes statements in new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {
        /**
         * x = new T()
         * add <x, o_i> to work list
         */
        @Override
        public Void visit(New stmt) {
            Obj newObj = heapModel.getObj(stmt);
            Pointer varPtr = pointerFlowGraph.getVarPtr(stmt.getLValue());
            workList.addEntry(varPtr, new PointsToSet(newObj));;
            return null;
        }

        /**
         * x = y
         * add new edge from y to x
         */
        @Override
        public Void visit(Copy stmt) {
            Pointer ptrTo = pointerFlowGraph.getVarPtr(stmt.getLValue());
            if (stmt.getRValue() != null) {
                Pointer ptrFrom = pointerFlowGraph.getVarPtr(stmt.getRValue());
                addPFGEdge(ptrFrom, ptrTo);
            }
            return null;
        }

        /**
         * Static Store: T.f = y
         * add new edge from y to T.f
         */
        @Override
        public Void visit(StoreField stmt) {
            JField field = stmt.getFieldRef().resolve();
            if (field.isStatic() && stmt.getRValue() != null) {
                Pointer ptrFrom = pointerFlowGraph.getVarPtr(stmt.getRValue());
                Pointer ptrTo = pointerFlowGraph.getStaticField(field);
                addPFGEdge(ptrFrom, ptrTo);
            }
            return null;
        }

        /**
         * Static Load: x = T.f
         * add new edge from T.f to x
         */
        @Override
        public Void visit(LoadField stmt) {
            JField field = stmt.getFieldRef().resolve();
            if (field.isStatic()) {
                Pointer ptrFrom = pointerFlowGraph.getStaticField(field);
                Pointer ptrTo =  pointerFlowGraph.getVarPtr(stmt.getLValue());
                addPFGEdge(ptrFrom, ptrTo);
            }
            return null;
        }

        /**
         * static invoke: r = T.m(a1, ..., an)
         * add new edge from a_1 to m_{p_1}, ... , from a_n to m_{p_n}
         * add new edge from m_{ret} to r
         */
        @Override
        public Void visit(Invoke stmt) {
            if (!stmt.isStatic()) {
                return null;
            }
            // add edges from parameters to arguments
            JMethod method = resolveCallee(null, stmt);
            Edge<Invoke, JMethod> callEdge = new Edge<>(CallKind.STATIC, stmt, method);
            addCallEdges(stmt, method, callEdge);
            return null;
        }
    }

    /**
     * Adds an edge "source -> target" to the PFG.
     */
    private void addPFGEdge(Pointer source, Pointer target) {
        if (pointerFlowGraph.addEdge(source, target)) {
            if (!source.getPointsToSet().isEmpty()) {
                workList.addEntry(target, source.getPointsToSet());
            }
        }
    }

    /**
     * Processes work-list entries until the work-list is empty.
     */
    private void analyze() {
        while (!workList.isEmpty()) {
            WorkList.Entry entry = workList.pollEntry();
            PointsToSet difference = propagate(entry.pointer(), entry.pointsToSet());

            // if pointer represents a variable x
            if (entry.pointer() instanceof VarPtr varPtr) {
                Var var = varPtr.getVar();
                for (Obj obj : difference) {
                    // x = y.f
                    for (LoadField loadField : var.getLoadFields()) {
                        if (!loadField.isStatic()) {
                            JField field = loadField.getFieldRef().resolve();
                            Pointer ptrFrom = pointerFlowGraph.getInstanceField(obj, field);
                            Pointer ptrTo =  pointerFlowGraph.getVarPtr(loadField.getLValue());
                            addPFGEdge(ptrFrom, ptrTo);
                        }
                    }
                    // x.f = y
                    for (StoreField storeField : var.getStoreFields()) {
                        if (!storeField.isStatic() && storeField.getRValue() != null) {
                            JField field = storeField.getFieldRef().resolve();
                            Pointer ptrFrom = pointerFlowGraph.getVarPtr(storeField.getRValue());
                            Pointer ptrTo = pointerFlowGraph.getInstanceField(obj, field);
                            addPFGEdge(ptrFrom, ptrTo);
                        }
                    }
                    // store array a[..] = x
                    for (StoreArray storeArray : var.getStoreArrays()) {
                        Pointer ptrFrom = pointerFlowGraph.getVarPtr(storeArray.getRValue());
                        Pointer ptrTo = pointerFlowGraph.getArrayIndex(obj);
                        addPFGEdge(ptrFrom, ptrTo);
                    }
                    // load array x = a[..]
                    for (LoadArray loadArray : var.getLoadArrays()) {
                        Pointer ptrFrom = pointerFlowGraph.getArrayIndex(obj);
                        Pointer ptrTo = pointerFlowGraph.getVarPtr(loadArray.getLValue());
                        addPFGEdge(ptrFrom, ptrTo);
                    }
                    // process call statement
                    processCall(var, obj);
                }
            }
        }
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        PointsToSet difference = new PointsToSet();
        PointsToSet ptn = pointer.getPointsToSet();
        // delta = pts - pt(n)
        for (Obj obj : pointsToSet) {
            if (ptn.contains(obj)) {
                continue;
            }
            difference.addObject(obj);
            ptn.addObject(obj);
        }

        // if delta is not empty, propagate it to more pointers
        if (!difference.isEmpty()) {
            for (Pointer successor : pointerFlowGraph.getSuccsOf(pointer)) {
                workList.addEntry(successor, pointsToSet);
            }
        }
        return difference;
    }

    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param var the variable that holds receiver objects
     * @param recv a new discovered object pointed by the variable.
     */
    private void processCall(Var var, Obj recv) {
        for (Invoke invoke : var.getInvokes()) {
            JMethod method = resolveCallee(recv, invoke);
            Edge<Invoke, JMethod> callEdge = getInvokeJMethodEdge(invoke, method);
            workList.addEntry(pointerFlowGraph.getVarPtr(method.getIR().getThis()), new PointsToSet(recv));
            addCallEdges(invoke, method, callEdge);
        }

    }

    private void addCallEdges(Invoke invoke, JMethod method, Edge<Invoke, JMethod> callEdge) {
        if (callGraph.addEdge(callEdge)) {
            addReachable(method);
            InvokeExp invokeExp = invoke.getInvokeExp();
            for (int i = 0; i < invokeExp.getArgCount(); i++) {
                Pointer ptrFrom = pointerFlowGraph.getVarPtr(invokeExp.getArg(i));
                Pointer ptrTo = pointerFlowGraph.getVarPtr(method.getIR().getParam(i));
                addPFGEdge(ptrFrom, ptrTo);
            }
            // add return edge from rv to left hand variable
            if (invoke.getLValue() != null) {
                for (Var returnVar : method.getIR().getReturnVars()) {
                    Pointer ptrFrom = pointerFlowGraph.getVarPtr(returnVar);
                    Pointer ptrTo = pointerFlowGraph.getVarPtr(invoke.getLValue());
                    addPFGEdge(ptrFrom, ptrTo);
                }
            }
        }
    }

    private static Edge<Invoke, JMethod> getInvokeJMethodEdge(Invoke invoke, JMethod method) {
        Edge<Invoke, JMethod> callEdge;
        if (invoke.isInterface()) {
            callEdge = new Edge<>(CallKind.INTERFACE, invoke, method);
        } else if (invoke.isDynamic()) {
            callEdge = new Edge<>(CallKind.DYNAMIC, invoke, method);
        } else if (invoke.isSpecial()) {
            callEdge = new Edge<>(CallKind.SPECIAL, invoke, method);
        } else if (invoke.isVirtual()) {
            callEdge = new Edge<>(CallKind.VIRTUAL, invoke, method);
        } else {
            callEdge = new Edge<>(CallKind.OTHER, invoke, method);
        }
        return callEdge;
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv     the receiver object of the method call. If the callSite
     *                 is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    private JMethod resolveCallee(Obj recv, Invoke callSite) {
        Type type = recv != null ? recv.getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    CIPTAResult getResult() {
        return new CIPTAResult(pointerFlowGraph, callGraph);
    }
}
