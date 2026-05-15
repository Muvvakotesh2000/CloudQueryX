package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.ArrayList;
import java.util.List;

public record ComparisonExpression(Op op, Expression left, Expression right) implements Expression {

    public enum Op {
        EQUAL("="), NOT_EQUAL("<>"), LESS_THAN("<"), GREATER_THAN(">"),
        LESS_THAN_OR_EQUAL("<="), GREATER_THAN_OR_EQUAL(">=");
        private final String symbol;
        Op(String symbol) { this.symbol = symbol; }
        public String symbol() { return symbol; }

        public Op negate() {
            return switch (this) {
                case EQUAL -> NOT_EQUAL;
                case NOT_EQUAL -> EQUAL;
                case LESS_THAN -> GREATER_THAN_OR_EQUAL;
                case GREATER_THAN -> LESS_THAN_OR_EQUAL;
                case LESS_THAN_OR_EQUAL -> GREATER_THAN;
                case GREATER_THAN_OR_EQUAL -> LESS_THAN;
            };
        }

        public Op flip() {
            return switch (this) {
                case LESS_THAN -> GREATER_THAN;
                case GREATER_THAN -> LESS_THAN;
                case LESS_THAN_OR_EQUAL -> GREATER_THAN_OR_EQUAL;
                case GREATER_THAN_OR_EQUAL -> LESS_THAN_OR_EQUAL;
                default -> this;
            };
        }
    }

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        SqlValue l = left.evaluate(row, schema);
        SqlValue r = right.evaluate(row, schema);
        if (l.isNull() || r.isNull()) return SqlValue.ofNull();
        int cmp = l.compareTo(r);
        boolean result = switch (op) {
            case EQUAL -> cmp == 0;
            case NOT_EQUAL -> cmp != 0;
            case LESS_THAN -> cmp < 0;
            case GREATER_THAN -> cmp > 0;
            case LESS_THAN_OR_EQUAL -> cmp <= 0;
            case GREATER_THAN_OR_EQUAL -> cmp >= 0;
        };
        return SqlValue.ofBoolean(result);
    }

    @Override
    public DataType getType(Schema inputSchema) {
        return DataType.BOOLEAN;
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
        return visitor.visitComparison(this);
    }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new ComparisonExpression(op, c.get(0), c.get(1));
    }

    @Override
    public String toString() {
        return "(" + left + " " + op.symbol() + " " + right + ")";
    }
}
