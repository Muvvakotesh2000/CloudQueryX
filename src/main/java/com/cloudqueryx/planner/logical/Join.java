package com.cloudqueryx.planner.logical;

import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;

import java.util.List;

public record Join(LogicalPlan left, LogicalPlan right, JoinType joinType, Expression condition)
        implements LogicalPlan {

    public enum JoinType {
        INNER, LEFT, RIGHT, FULL, CROSS
    }

    @Override
    public Schema getOutputSchema() {
        return left.getOutputSchema().merge(right.getOutputSchema());
    }

    @Override
    public List<LogicalPlan> getChildren() {
        return List.of(left, right);
    }

    @Override
    public <R> R accept(LogicalPlanVisitor<R> visitor) {
        return visitor.visitJoin(this);
    }

    @Override
    public String toString() {
        return "Join[" + joinType +
                (condition != null ? ", on=" + condition : "") + "]";
    }
}
