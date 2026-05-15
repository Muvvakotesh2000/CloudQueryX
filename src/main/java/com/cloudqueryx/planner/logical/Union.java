package com.cloudqueryx.planner.logical;

import com.cloudqueryx.common.Schema;

import java.util.List;

public record Union(LogicalPlan left, LogicalPlan right, boolean all) implements LogicalPlan {

    @Override
    public Schema getOutputSchema() {
        return left.getOutputSchema();
    }

    @Override
    public List<LogicalPlan> getChildren() {
        return List.of(left, right);
    }

    @Override
    public <R> R accept(LogicalPlanVisitor<R> visitor) {
        return visitor.visitUnion(this);
    }

    @Override
    public String toString() {
        return "Union[" + (all ? "ALL" : "DISTINCT") + "]";
    }
}
