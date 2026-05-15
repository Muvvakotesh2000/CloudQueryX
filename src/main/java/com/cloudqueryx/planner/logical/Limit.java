package com.cloudqueryx.planner.logical;

import com.cloudqueryx.common.Schema;

import java.util.List;

public record Limit(LogicalPlan input, long limit, long offset) implements LogicalPlan {

    public Limit(LogicalPlan input, long limit) {
        this(input, limit, 0);
    }

    @Override
    public Schema getOutputSchema() {
        return input.getOutputSchema();
    }

    @Override
    public List<LogicalPlan> getChildren() {
        return List.of(input);
    }

    @Override
    public <R> R accept(LogicalPlanVisitor<R> visitor) {
        return visitor.visitLimit(this);
    }

    @Override
    public String toString() {
        return "Limit[" + limit + (offset > 0 ? ", offset=" + offset : "") + "]";
    }
}
