package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.List;

public record Literal(SqlValue value) implements Expression {

    public static Literal ofInt(int v) { return new Literal(SqlValue.ofInt(v)); }
    public static Literal ofLong(long v) { return new Literal(SqlValue.ofLong(v)); }
    public static Literal ofDouble(double v) { return new Literal(SqlValue.ofDouble(v)); }
    public static Literal ofString(String v) { return new Literal(SqlValue.ofString(v)); }
    public static Literal ofBoolean(boolean v) { return new Literal(SqlValue.ofBoolean(v)); }
    public static Literal ofNull() { return new Literal(SqlValue.ofNull()); }

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        return value;
    }

    @Override
    public DataType getType(Schema inputSchema) {
        return value.getType();
    }

    @Override
    public List<ColumnRef> getReferencedColumns() {
        return List.of();
    }

    @Override
    public List<Expression> getChildren() {
        return List.of();
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitLiteral(this);
    }

    @Override
    public Expression rebuildWithChildren(List<Expression> newChildren) {
        return this;
    }

    @Override
    public String toString() {
        return value.getType() == DataType.VARCHAR ? "'" + value + "'" : value.toString();
    }
}
