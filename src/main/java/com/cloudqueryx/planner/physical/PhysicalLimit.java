package com.cloudqueryx.planner.physical;

import com.cloudqueryx.common.Schema;

import java.util.List;

public record PhysicalLimit(PhysicalPlan input, long limit, long offset) implements PhysicalPlan {

    @Override public Schema getOutputSchema() { return input.getOutputSchema(); }
    @Override public List<PhysicalPlan> getChildren() { return List.of(input); }
    @Override public double estimatedCost() { return input.estimatedCost() + limit; }
    @Override public long estimatedRowCount() { return Math.min(limit, input.estimatedRowCount()); }

    @Override
    public <R> R accept(PhysicalPlanVisitor<R> visitor) {
        return visitor.visitPhysicalLimit(this);
    }

    @Override
    public String toString() {
        return "Limit[" + limit + (offset > 0 ? ", offset=" + offset : "") + "]";
    }
}
