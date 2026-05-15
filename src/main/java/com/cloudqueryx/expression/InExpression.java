package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.ArrayList;
import java.util.List;

public record InExpression(Expression value, List<Expression> list, boolean negated)
        implements Expression {

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        SqlValue v = value.evaluate(row, schema);
        if (v.isNull()) return SqlValue.ofNull();
        boolean found = false;
        for (Expression item : list) {
            SqlValue itemVal = item.evaluate(row, schema);
            if (!itemVal.isNull() && v.compareTo(itemVal) == 0) {
                found = true;
                break;
            }
        }
        return SqlValue.ofBoolean(negated != found);
    }

    @Override public DataType getType(Schema s) { return DataType.BOOLEAN; }

    @Override
    public List<ColumnRef> getReferencedColumns() {
        List<ColumnRef> refs = new ArrayList<>(value.getReferencedColumns());
        for (Expression item : list) refs.addAll(item.getReferencedColumns());
        return refs;
    }

    @Override
    public List<Expression> getChildren() {
        List<Expression> children = new ArrayList<>();
        children.add(value);
        children.addAll(list);
        return children;
    }

    @Override public <R> R accept(ExpressionVisitor<R> v) { return v.visitIn(this); }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new InExpression(c.get(0), c.subList(1, c.size()), negated);
    }

    @Override
    public String toString() {
        return value + (negated ? " NOT IN " : " IN ") + "(" +
                String.join(", ", list.stream().map(Object::toString).toList()) + ")";
    }
}
