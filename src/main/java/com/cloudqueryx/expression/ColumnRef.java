package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.List;

public record ColumnRef(String tableName, String columnName) implements Expression {

    public ColumnRef(String columnName) {
        this(null, columnName);
    }

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        String lookup = tableName != null ? tableName + "." + columnName : columnName;
        int idx = schema.getColumnIndex(lookup);
        return row.getValue(idx);
    }

    @Override
    public DataType getType(Schema inputSchema) {
        String lookup = tableName != null ? tableName + "." + columnName : columnName;
        return inputSchema.getColumn(lookup).type();
    }

    @Override
    public List<ColumnRef> getReferencedColumns() {
        return List.of(this);
    }

    @Override
    public List<Expression> getChildren() {
        return List.of();
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitColumnRef(this);
    }

    @Override
    public Expression rebuildWithChildren(List<Expression> newChildren) {
        return this;
    }

    public String qualifiedName() {
        return tableName != null ? tableName + "." + columnName : columnName;
    }

    @Override
    public String toString() {
        return qualifiedName();
    }
}
