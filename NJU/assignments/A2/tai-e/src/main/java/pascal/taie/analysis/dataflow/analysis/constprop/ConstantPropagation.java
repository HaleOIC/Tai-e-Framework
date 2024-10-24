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

package pascal.taie.analysis.dataflow.analysis.constprop;

import fj.P;
import pascal.taie.analysis.dataflow.analysis.AbstractDataflowAnalysis;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.ArithmeticExp;
import pascal.taie.ir.exp.BinaryExp;
import pascal.taie.ir.exp.BitwiseExp;
import pascal.taie.ir.exp.ConditionExp;
import pascal.taie.ir.exp.Exp;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.ShiftExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.DefinitionStmt;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;

import java.util.Map;
import java.util.Objects;

public class ConstantPropagation extends
        AbstractDataflowAnalysis<Stmt, CPFact> {

    public static final String ID = "constprop";

    public ConstantPropagation(AnalysisConfig config) {
        super(config);
    }

    @Override
    public boolean isForward() {
        return true;
    }

    @Override
    public CPFact newBoundaryFact(CFG<Stmt> cfg) {
        // return an empty map
        CPFact fact = new CPFact();
        cfg.getIR().getParams().forEach(param -> {
            if (canHoldInt(param)) {
                fact.update(param, Value.getNAC());
            }
        });
        return fact;
    }

    @Override
    public CPFact newInitialFact() {
        // return an empty map
        return new CPFact();
    }

    @Override
    public void meetInto(CPFact fact, CPFact target) {
        fact.forEach((var, factValue) -> {
            Value targetValue = target.get(var);
            Value newValue = (targetValue != null) ? meetValue(factValue, targetValue) : factValue;
            target.update(var, newValue);
        });
    }

    /**
     * Meets two Values.
     */
    public Value meetValue(Value v1, Value v2) {
        // NAC ^ v = NAC
        if (v1.isNAC() || v2.isNAC()) {
            return Value.getNAC();
        }
        // UNDEF ^ v = v
        if (v1.isUndef()) {
            return v2;
        }
        if (v2.isUndef()) {
            return v1;
        }
        // c ^ v = ?
        if (v1.equals(v2)) {
            // c ^ c = c
            return v1;
        } else {
            // c1 ^ c2 = NAC
            return Value.getNAC();
        }
    }

    @Override
    public boolean transferNode(Stmt stmt, CPFact in, CPFact out) {
        // no assignment statement
        if (stmt.getDef().isEmpty()) {
            if (!in.equals(out)) {
                for (Var var : in.keySet()) {
                    out.update(var, in.get(var));
                }
                return true;
            }
            return false;
        }

        // Out[B] = gen_b U (In[B] - kill_B)
        CPFact tempOut = in.copy();

        // Remove left definition of statement
        Var def = (Var) stmt.getDef().get();
        if (out.keySet().contains(def)) {
            tempOut.remove(def);
        }

        // Evaluate each expression under current In environment
        for (Exp exp : stmt.getUses()) {
            tempOut.update(def, evaluate(exp, in));
        }

        // check whether termination
        if (!(tempOut.equals(out))) {
            for (Var var : tempOut.keySet()) {
                out.update(var, tempOut.get(var));
            }
        }
        return false;
    }

    /**
     * @return true if the given variable can hold integer value, otherwise false.
     */
    public static boolean canHoldInt(Var var) {
        Type type = var.getType();
        if (type instanceof PrimitiveType) {
            switch ((PrimitiveType) type) {
                case BYTE:
                case SHORT:
                case INT:
                case CHAR:
                case BOOLEAN:
                    return true;
            }
        }
        return false;
    }

    /**
     * Evaluates the {@link Value} of given expression.
     *
     * @param exp the expression to be evaluated
     * @param in  IN fact of the statement
     * @return the resulting {@link Value}
     */
    public static Value evaluate(Exp exp, CPFact in) {
        // x = c -> CONSTANT
        if (exp instanceof IntLiteral intLiteral) {
            return Value.makeConstant(intLiteral.getValue());
        }
        // x = y -> val(y)
        if (exp instanceof Var var) {
            return in.get(var);
        }
        // x = y op z
        if (exp instanceof BinaryExp binaryExp) {
            // val(y) op val(z)
            Value operand1 = in.get(binaryExp.getOperand1());
            Value operand2 = in.get(binaryExp.getOperand2());
            BinaryExp.Op op = binaryExp.getOperator();

            // exceptional case(div or rem zero)
            if (operand2.isConstant() && operand2.getConstant() == 0) {
                if (op.equals(ArithmeticExp.Op.DIV) || op.equals(ArithmeticExp.Op.REM)) {
                    return Value.getUndef();
                }
            }

            // val(y) or val(z) is NAC
            if (operand1.isNAC() || operand2.isNAC()) {
                return Value.getNAC();
            }

            // val(y) or val(z) is UNDEF
            if (operand1.isUndef() || operand2.isUndef()) {
                return Value.getUndef();
            }

            // common case
            if (operand1.isConstant() && operand2.isConstant()) {
                int val1 = operand1.getConstant();
                int val2 = operand2.getConstant();

                // bitwise operator
                if (op instanceof BitwiseExp.Op bitOp) {
                    return switch (bitOp) {
                        case AND -> Value.makeConstant(val1 & val2);
                        case OR -> Value.makeConstant(val1 | val2);
                        case XOR -> Value.makeConstant(val1 ^ val2);
                    };
                }

                // arithmetic operator
                if (op instanceof ArithmeticExp.Op arOp) {
                    return switch ((ArithmeticExp.Op) arOp) {
                        case ADD -> Value.makeConstant(val1 + val2);
                        case SUB -> Value.makeConstant(val1 - val2);
                        case MUL -> Value.makeConstant(val1 * val2);
                        case DIV -> Value.makeConstant(val1 / val2);
                        case REM -> Value.makeConstant(val1 % val2);
                    };
                }

                // condition operator
                if (op instanceof ConditionExp.Op condOp) {
                    return switch (condOp) {
                        case EQ -> Value.makeConstant(val1 == val2 ? 1 : 0);
                        case NE -> Value.makeConstant(val1 != val2 ? 1 : 0);
                        case LT -> Value.makeConstant(val1 < val2 ? 1 : 0);
                        case GT -> Value.makeConstant(val1 > val2 ? 1 : 0);
                        case LE -> Value.makeConstant(val1 <= val2 ? 1 : 0);
                        case GE -> Value.makeConstant(val1 >= val2 ? 1 : 0);
                    };
                }

                // shift operator
                if (op instanceof ShiftExp.Op shiftOp) {
                    return switch (shiftOp) {
                        case SHL -> Value.makeConstant(val1 << val2);
                        case SHR -> Value.makeConstant(val1 >> val2);
                        case USHR -> Value.makeConstant(val1 >>> val2);
                    };
                }
            }
        }
        return Value.getNAC();
    }
}
