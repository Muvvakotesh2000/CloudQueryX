package com.cloudqueryx.planner.logical;

import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;

import java.util.List;

public record Filter(LogicalPlan input, Expression condition) implements LogicalPlan {

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
        return visitor.visitFilter(this);
    }

    @Override
    public String toString() {
        return "Filter[" + condition + "]";
    }
}
