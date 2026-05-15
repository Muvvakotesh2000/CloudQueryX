package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.ArrayList;
import java.util.List;

public record CaseExpression(
        Expression operand,
        List<WhenClause> whenClauses,
        Expression elseExpression
) implements Expression {

    public record WhenClause(Expression condition, Expression result) {}

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        if (operand != null) {
            SqlValue opVal = operand.evaluate(row, schema);
            for (WhenClause when : whenClauses) {
                SqlValue whenVal = when.condition().evaluate(row, schema);
                if (!opVal.isNull() && !whenVal.isNull() && opVal.compareTo(whenVal) == 0) {
                    return when.result().evaluate(row, schema);
                }
            }
        } else {
            for (WhenClause when : whenClauses) {
                SqlValue cond = when.condition().evaluate(row, schema);
                if (!cond.isNull() && cond.asBoolean()) {
                    return when.result().evaluate(row, schema);
                }
            }
        }
        return elseExpression != null ? elseExpression.evaluate(row, schema) : SqlValue.ofNull();
    }

    @Override
    public DataType getType(Schema inputSchema) {
        if (!whenClauses.isEmpty()) {
            return whenClauses.get(0).result().getType(inputSchema);
        }
        return elseExpression != null ? elseExpression.getType(inputSchema) : DataType.NULL;
    }

    @Override
    public List<ColumnRef> getReferencedColumns() {
        List<ColumnRef> refs = new ArrayList<>();
        if (operand != null) refs.addAll(operand.getReferencedColumns());
        for (WhenClause when : whenClauses) {
            refs.addAll(when.condition().getReferencedColumns());
            refs.addAll(when.result().getReferencedColumns());
        }
        if (elseExpression != null) refs.addAll(elseExpression.getReferencedColumns());
        return refs;
    }

    @Override
    public List<Expression> getChildren() {
        List<Expression> children = new ArrayList<>();
        if (operand != null) children.add(operand);
        for (WhenClause when : whenClauses) {
            children.add(when.condition());
            children.add(when.result());
        }
        if (elseExpression != null) children.add(elseExpression);
        return children;
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitCase(this);
    }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        int idx = 0;
        Expression newOperand = operand != null ? c.get(idx++) : null;
        List<WhenClause> newClauses = new ArrayList<>();
        for (int i = 0; i < whenClauses.size(); i++) {
            newClauses.add(new WhenClause(c.get(idx++), c.get(idx++)));
        }
        Expression newElse = elseExpression != null ? c.get(idx) : null;
        return new CaseExpression(newOperand, newClauses, newElse);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CASE");
        if (operand != null) sb.append(" ").append(operand);
        for (WhenClause when : whenClauses) {
            sb.append(" WHEN ").append(when.condition()).append(" THEN ").append(when.result());
        }
        if (elseExpression != null) sb.append(" ELSE ").append(elseExpression);
        sb.append(" END");
        return sb.toString();
    }
}
