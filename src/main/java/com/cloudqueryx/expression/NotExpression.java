package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.List;

public record NotExpression(Expression operand) implements Expression {

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        SqlValue val = operand.evaluate(row, schema);
        if (val.isNull()) return SqlValue.ofNull();
        return SqlValue.ofBoolean(!val.asBoolean());
    }

    @Override
    public DataType getType(Schema inputSchema) {
        return DataType.BOOLEAN;
    }

    @Override
    public List<ColumnRef> getReferencedColumns() {
        return operand.getReferencedColumns();
    }

    @Override
    public List<Expression> getChildren() {
        return List.of(operand);
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitNot(this);
    }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new NotExpression(c.get(0));
    }

    @Override
    public String toString() {
        return "(NOT " + operand + ")";
    }
}
