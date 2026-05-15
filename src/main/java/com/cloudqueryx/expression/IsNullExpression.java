package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.List;

public record IsNullExpression(Expression operand, boolean negated) implements Expression {

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        SqlValue val = operand.evaluate(row, schema);
        return SqlValue.ofBoolean(negated != val.isNull());
    }

    @Override public DataType getType(Schema s) { return DataType.BOOLEAN; }

    @Override public List<ColumnRef> getReferencedColumns() { return operand.getReferencedColumns(); }
    @Override public List<Expression> getChildren() { return List.of(operand); }
    @Override public <R> R accept(ExpressionVisitor<R> v) { return v.visitIsNull(this); }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new IsNullExpression(c.get(0), negated);
    }

    @Override
    public String toString() {
        return operand + (negated ? " IS NOT NULL" : " IS NULL");
    }
}
