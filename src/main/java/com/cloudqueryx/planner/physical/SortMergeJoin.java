package com.cloudqueryx.planner.physical;

import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;
import com.cloudqueryx.planner.logical.Join;

import java.util.List;

public record SortMergeJoin(
        PhysicalPlan left,
        PhysicalPlan right,
        Expression condition,
        Join.JoinType joinType
) implements PhysicalPlan {

    @Override
    public Schema getOutputSchema() {
        return left.getOutputSchema().merge(right.getOutputSchema());
    }

    @Override
    public List<PhysicalPlan> getChildren() {
        return List.of(left, right);
    }

    @Override
    public double estimatedCost() {
        double sortLeft = left.estimatedRowCount() * Math.log(left.estimatedRowCount() + 1) * 2.0;
        double sortRight = right.estimatedRowCount() * Math.log(right.estimatedRowCount() + 1) * 2.0;
        double merge = left.estimatedRowCount() + right.estimatedRowCount();
        return left.estimatedCost() + right.estimatedCost() + sortLeft + sortRight + merge;
    }

    @Override
    public long estimatedRowCount() {
        return Math.max(1, Math.min(left.estimatedRowCount(), right.estimatedRowCount()));
    }

    @Override
    public <R> R accept(PhysicalPlanVisitor<R> visitor) {
        return visitor.visitSortMergeJoin(this);
    }

    @Override
    public String toString() {
        return "SortMergeJoin[" + joinType + ", on=" + condition + "]";
    }
}
