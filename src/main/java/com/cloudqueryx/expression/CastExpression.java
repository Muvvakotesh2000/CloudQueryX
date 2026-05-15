package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record CastExpression(Expression operand, DataType targetType) implements Expression {

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        SqlValue val = operand.evaluate(row, schema);
        if (val.isNull()) return SqlValue.ofNull();
        return switch (targetType) {
            case INTEGER -> SqlValue.ofInt(val.asInt());
            case BIGINT -> SqlValue.ofLong(val.asLong());
            case DOUBLE -> SqlValue.ofDouble(val.asDouble());
            case DECIMAL -> SqlValue.ofDecimal(new BigDecimal(val.asString()));
            case VARCHAR -> SqlValue.ofString(val.asString());
            case BOOLEAN -> SqlValue.ofBoolean(val.asBoolean());
            case DATE -> SqlValue.ofDate(LocalDate.parse(val.asString()));
            case TIMESTAMP -> SqlValue.ofTimestamp(LocalDateTime.parse(val.asString()));
            case NULL -> SqlValue.ofNull();
        };
    }

    @Override public DataType getType(Schema s) { return targetType; }
    @Override public List<ColumnRef> getReferencedColumns() { return operand.getReferencedColumns(); }
    @Override public List<Expression> getChildren() { return List.of(operand); }
    @Override public <R> R accept(ExpressionVisitor<R> v) { return v.visitCast(this); }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new CastExpression(c.get(0), targetType);
    }

    @Override
    public String toString() {
        return "CAST(" + operand + " AS " + targetType + ")";
    }
}
