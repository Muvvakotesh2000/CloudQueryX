package com.cloudqueryx.planner.physical;

import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;
import com.cloudqueryx.planner.logical.Join;

import java.util.List;

public record NestedLoopJoin(
        PhysicalPlan outer,
        PhysicalPlan inner,
        Expression condition,
        Join.JoinType joinType
) implements PhysicalPlan {

    @Override
    public Schema getOutputSchema() {
        return outer.getOutputSchema().merge(inner.getOutputSchema());
    }

    @Override
    public List<PhysicalPlan> getChildren() {
        return List.of(outer, inner);
    }

    @Override
    public double estimatedCost() {
        return outer.estimatedCost() + inner.estimatedCost()
                + outer.estimatedRowCount() * inner.estimatedRowCount() * 0.5;
    }

    @Override
    public long estimatedRowCount() {
        if (joinType == Join.JoinType.CROSS) {
            return outer.estimatedRowCount() * inner.estimatedRowCount();
        }
        return Math.max(1, Math.min(outer.estimatedRowCount(), inner.estimatedRowCount()));
    }

    @Override
    public <R> R accept(PhysicalPlanVisitor<R> visitor) {
        return visitor.visitNestedLoopJoin(this);
    }

    @Override
    public String toString() {
        return "NestedLoopJoin[" + joinType +
                (condition != null ? ", on=" + condition : "") + "]";
    }
}
