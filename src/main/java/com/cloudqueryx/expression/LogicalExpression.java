package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.ArrayList;
import java.util.List;

public record LogicalExpression(Op op, Expression left, Expression right) implements Expression {

    public enum Op {
        AND("AND"), OR("OR");
        private final String symbol;
        Op(String symbol) { this.symbol = symbol; }
        public String symbol() { return symbol; }
    }

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        SqlValue l = left.evaluate(row, schema);
        if (op == Op.AND && !l.isNull() && !l.asBoolean()) return SqlValue.ofBoolean(false);
        if (op == Op.OR && !l.isNull() && l.asBoolean()) return SqlValue.ofBoolean(true);

        SqlValue r = right.evaluate(row, schema);
        if (l.isNull() || r.isNull()) return SqlValue.ofNull();

        return switch (op) {
            case AND -> SqlValue.ofBoolean(l.asBoolean() && r.asBoolean());
            case OR -> SqlValue.ofBoolean(l.asBoolean() || r.asBoolean());
        };
    }

    @Override
    public DataType getType(Schema inputSchema) {
        return DataType.BOOLEAN;
    }

    @Override
    public List<ColumnRef> getReferencedColumns() {
        List<ColumnRef> refs = new ArrayList<>(left.getReferencedColumns());
        refs.addAll(right.getReferencedColumns());
        return refs;
    }

    @Override
    public List<Expression> getChildren() {
        return List.of(left, right);
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitLogical(this);
    }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new LogicalExpression(op, c.get(0), c.get(1));
    }

    public static Expression and(Expression left, Expression right) {
        return new LogicalExpression(Op.AND, left, right);
    }

    public static Expression or(Expression left, Expression right) {
        return new LogicalExpression(Op.OR, left, right);
    }

    public static Expression conjunct(List<Expression> conditions) {
        if (conditions.isEmpty()) return Literal.ofBoolean(true);
        Expression result = conditions.get(0);
        for (int i = 1; i < conditions.size(); i++) {
            result = and(result, conditions.get(i));
        }
        return result;
    }

    public static List<Expression> flattenConjuncts(Expression expr) {
        List<Expression> result = new ArrayList<>();
        flattenConjunctsInternal(expr, result);
        return result;
    }

    private static void flattenConjunctsInternal(Expression expr, List<Expression> result) {
        if (expr instanceof LogicalExpression le && le.op() == Op.AND) {
            flattenConjunctsInternal(le.left(), result);
            flattenConjunctsInternal(le.right(), result);
        } else {
            result.add(expr);
        }
    }

    @Override
    public String toString() {
        return "(" + left + " " + op.symbol() + " " + right + ")";
    }
}
