package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.ArrayList;
import java.util.List;

public record BetweenExpression(Expression value, Expression lower, Expression upper, boolean negated)
        implements Expression {

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        SqlValue v = value.evaluate(row, schema);
        SqlValue lo = lower.evaluate(row, schema);
        SqlValue hi = upper.evaluate(row, schema);
        if (v.isNull() || lo.isNull() || hi.isNull()) return SqlValue.ofNull();
        boolean inRange = v.compareTo(lo) >= 0 && v.compareTo(hi) <= 0;
        return SqlValue.ofBoolean(negated != inRange);
    }

    @Override public DataType getType(Schema s) { return DataType.BOOLEAN; }

    @Override
    public List<ColumnRef> getReferencedColumns() {
        List<ColumnRef> refs = new ArrayList<>(value.getReferencedColumns());
        refs.addAll(lower.getReferencedColumns());
        refs.addAll(upper.getReferencedColumns());
        return refs;
    }

    @Override public List<Expression> getChildren() { return List.of(value, lower, upper); }
    @Override public <R> R accept(ExpressionVisitor<R> v) { return v.visitBetween(this); }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new BetweenExpression(c.get(0), c.get(1), c.get(2), negated);
    }

    @Override
    public String toString() {
        return value + (negated ? " NOT BETWEEN " : " BETWEEN ") + lower + " AND " + upper;
    }
}
