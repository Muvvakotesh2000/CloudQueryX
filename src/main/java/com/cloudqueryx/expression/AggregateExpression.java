package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.List;

public record AggregateExpression(Function function, Expression argument, boolean distinct)
        implements Expression {

    public enum Function {
        COUNT, SUM, AVG, MIN, MAX
    }

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        throw new UnsupportedOperationException(
                "Aggregate expressions must be evaluated by the aggregate operator");
    }

    @Override
    public DataType getType(Schema inputSchema) {
        return switch (function) {
            case COUNT -> DataType.BIGINT;
            case SUM, AVG -> argument != null ? DataType.promote(argument.getType(inputSchema), DataType.DOUBLE) : DataType.BIGINT;
            case MIN, MAX -> argument != null ? argument.getType(inputSchema) : DataType.NULL;
        };
    }

    @Override
    public List<ColumnRef> getReferencedColumns() {
        return argument != null ? argument.getReferencedColumns() : List.of();
    }

    @Override
    public List<Expression> getChildren() {
        return argument != null ? List.of(argument) : List.of();
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitAggregate(this);
    }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new AggregateExpression(function, c.isEmpty() ? null : c.get(0), distinct);
    }

    @Override
    public String toString() {
        String arg = argument != null ? argument.toString() : "*";
        return function + "(" + (distinct ? "DISTINCT " : "") + arg + ")";
    }
}
