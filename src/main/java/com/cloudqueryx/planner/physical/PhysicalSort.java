package com.cloudqueryx.planner.physical;

import com.cloudqueryx.common.Schema;
import com.cloudqueryx.planner.logical.Sort.SortKey;

import java.util.List;

public record PhysicalSort(PhysicalPlan input, List<SortKey> sortKeys) implements PhysicalPlan {

    @Override public Schema getOutputSchema() { return input.getOutputSchema(); }
    @Override public List<PhysicalPlan> getChildren() { return List.of(input); }

    @Override
    public double estimatedCost() {
        long n = input.estimatedRowCount();
        return input.estimatedCost() + n * Math.log(n + 1) * 2.0;
    }

    @Override public long estimatedRowCount() { return input.estimatedRowCount(); }

    @Override
    public <R> R accept(PhysicalPlanVisitor<R> visitor) {
        return visitor.visitPhysicalSort(this);
    }

    @Override
    public String toString() {
        return "Sort[" + sortKeys.stream()
                .map(k -> k.expression() + " " + k.direction())
                .toList() + "]";
    }
}
