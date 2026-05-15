package com.cloudqueryx.planner.physical;

import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;

import java.util.List;

public record PhysicalFilter(PhysicalPlan input, Expression condition, double selectivity)
        implements PhysicalPlan {

    public PhysicalFilter(PhysicalPlan input, Expression condition) {
        this(input, condition, 0.3);
    }

    @Override public Schema getOutputSchema() { return input.getOutputSchema(); }
    @Override public List<PhysicalPlan> getChildren() { return List.of(input); }
    @Override public double estimatedCost() { return input.estimatedCost() + input.estimatedRowCount() * 0.1; }
    @Override public long estimatedRowCount() { return Math.max(1, (long)(input.estimatedRowCount() * selectivity)); }

    @Override
    public <R> R accept(PhysicalPlanVisitor<R> visitor) {
        return visitor.visitPhysicalFilter(this);
    }

    @Override
    public String toString() {
        return "Filter[" + condition + "]";
    }
}
