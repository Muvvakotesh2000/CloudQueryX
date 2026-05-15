package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.ArrayList;
import java.util.List;

public record ArithmeticExpression(Op op, Expression left, Expression right) implements Expression {

    public enum Op {
        ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/"), MODULO("%");
        private final String symbol;
        Op(String symbol) { this.symbol = symbol; }
        public String symbol() { return symbol; }
    }

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        SqlValue l = left.evaluate(row, schema);
        SqlValue r = right.evaluate(row, schema);
        return switch (op) {
            case ADD -> l.add(r);
            case SUBTRACT -> l.subtract(r);
            case MULTIPLY -> l.multiply(r);
            case DIVIDE -> l.divide(r);
            case MODULO -> l.modulo(r);
        };
    }

    @Override
    public DataType getType(Schema inputSchema) {
        return DataType.promote(left.getType(inputSchema), right.getType(inputSchema));
    }

    @Override
    public List<ColumnRef> getReferencedColumns() {
        List<ColumnRef> refs = new ArrayList<>(left.getReferencedColumns());
        refs.addAll(right.getReferencedColumns());
        return refs;
    }

    @Override
    public List<Expression> getChildren() {
        return List.of(left, right);
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitArithmetic(this);
    }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new ArithmeticExpression(op, c.get(0), c.get(1));
    }

    @Override
    public String toString() {
        return "(" + left + " " + op.symbol() + " " + right + ")";
    }
}
