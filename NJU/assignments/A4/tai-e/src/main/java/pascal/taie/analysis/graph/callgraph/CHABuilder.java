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

package pascal.taie.analysis.graph.callgraph;

import pascal.taie.World;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Implementation of the CHA algorithm.
 */
class CHABuilder implements CGBuilder<Invoke, JMethod> {

    private ClassHierarchy hierarchy;

    @Override
    public CallGraph<Invoke, JMethod> build() {
        hierarchy = World.get().getClassHierarchy();
        return buildCallGraph(World.get().getMainMethod());
    }

    private CallGraph<Invoke, JMethod> buildCallGraph(JMethod entry) {
        DefaultCallGraph callGraph = new DefaultCallGraph();
        callGraph.addEntryMethod(entry);

        Queue<JMethod> workList = new ArrayDeque<>();
        workList.add(entry);
        while (!workList.isEmpty()) {
            JMethod currentMethod = workList.remove();
            if (callGraph.addReachableMethod(currentMethod)) {
                for (Invoke invoke : callGraph.getCallSitesIn(currentMethod)) {
                    for (JMethod m : resolve(invoke)) {
                        Edge<Invoke, JMethod> newEdge = new Edge<>(CallGraphs.getCallKind(invoke), invoke, m);
                        callGraph.addEdge(newEdge);
                        workList.add(m);
                    }
                }
            }
        }
        return callGraph;
    }

    /**
     * Resolves call targets (callees) of a call site via CHA.
     */
    private Set<JMethod> resolve(Invoke callSite) {
        Set<JMethod> methods = new HashSet<>();
        Subsignature methodSignature = callSite.getInvokeExp().getMethodRef().getSubsignature();
        JClass declaringClass = callSite.getInvokeExp().getMethodRef().getDeclaringClass();

        switch (CallGraphs.getCallKind(callSite)) {
            case STATIC -> {
                JMethod staticMethod = declaringClass.getDeclaredMethod(methodSignature);
                methods.add(staticMethod);
            }
            case SPECIAL -> methods.add(dispatch(declaringClass, methodSignature));
            case VIRTUAL, INTERFACE -> addDispatchMethodsRecursively(declaringClass, methodSignature, methods);
        }
        return methods;
    }

    private void addDispatchMethodsRecursively(JClass startClass, Subsignature signature, Set<JMethod> methods) {
        JMethod dispatchMethod = dispatch(startClass, signature);
        if (dispatchMethod != null) {
            methods.add(dispatchMethod);
        }

        Queue<JClass> queue = new ArrayDeque<>();
        queue.add(startClass);

        while (!queue.isEmpty()) {
            JClass currentClass = queue.poll();
            Set<JClass> subTypes = new HashSet<>();

            subTypes.addAll(hierarchy.getDirectSubclassesOf(currentClass));
            subTypes.addAll(hierarchy.getDirectImplementorsOf(currentClass));
            subTypes.addAll(hierarchy.getDirectSubinterfacesOf(currentClass));

            for (JClass subType : subTypes) {
                dispatchMethod = dispatch(subType, signature);
                if (dispatchMethod != null) {
                    methods.add(dispatchMethod);
                }
                queue.add(subType);
            }
        }
    }

    /**
     * Looks up the target method based on given class and method subsignature.
     *
     * @return the dispatched target method, or null if no satisfying method
     * can be found.
     */
    private JMethod dispatch(JClass jclass, Subsignature subsignature) {
        // if class contains non-abstract method m' that has the same name and descriptor as m
        JMethod currentLevelMethod = jclass.getDeclaredMethod(subsignature);
        if (currentLevelMethod != null && !currentLevelMethod.isAbstract()) {
            return currentLevelMethod;
        }
        // otherwise, if current level does not have a super class
        // it should return null or superclass dispatch
        if (jclass.getSuperClass() != null) {
            return dispatch(jclass.getSuperClass(), subsignature);
        }
        return null;
    }
}
