package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public record LikeExpression(Expression value, Expression pattern, boolean negated)
        implements Expression {

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        SqlValue v = value.evaluate(row, schema);
        SqlValue p = pattern.evaluate(row, schema);
        if (v.isNull() || p.isNull()) return SqlValue.ofNull();

        String regex = likeToRegex(p.asString());
        boolean matches = Pattern.matches(regex, v.asString());
        return SqlValue.ofBoolean(negated != matches);
    }

    private static String likeToRegex(String like) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < like.length(); i++) {
            char c = like.charAt(i);
            switch (c) {
                case '%' -> sb.append(".*");
                case '_' -> sb.append(".");
                case '\\' -> {
                    if (i + 1 < like.length()) {
                        sb.append(Pattern.quote(String.valueOf(like.charAt(++i))));
                    }
                }
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append("$");
        return sb.toString();
    }

    @Override public DataType getType(Schema s) { return DataType.BOOLEAN; }

    @Override
    public List<ColumnRef> getReferencedColumns() {
        List<ColumnRef> refs = new ArrayList<>(value.getReferencedColumns());
        refs.addAll(pattern.getReferencedColumns());
        return refs;
    }

    @Override public List<Expression> getChildren() { return List.of(value, pattern); }
    @Override public <R> R accept(ExpressionVisitor<R> v) { return v.visitLike(this); }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new LikeExpression(c.get(0), c.get(1), negated);
    }

    @Override
    public String toString() {
        return value + (negated ? " NOT LIKE " : " LIKE ") + pattern;
    }
}
