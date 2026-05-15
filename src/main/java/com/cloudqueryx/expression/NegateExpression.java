package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.List;

public record NegateExpression(Expression operand) implements Expression {

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        return operand.evaluate(row, schema).negate();
    }

    @Override
    public DataType getType(Schema inputSchema) {
        return operand.getType(inputSchema);
    }

    @Override public List<ColumnRef> getReferencedColumns() { return operand.getReferencedColumns(); }
    @Override public List<Expression> getChildren() { return List.of(operand); }
    @Override public <R> R accept(ExpressionVisitor<R> v) { return v.visitNegate(this); }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new NegateExpression(c.get(0));
    }

    @Override
    public String toString() {
        return "(-" + operand + ")";
    }
}
