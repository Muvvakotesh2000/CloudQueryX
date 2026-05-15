package com.cloudqueryx.planner.logical;

import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;

import java.util.List;

public record Sort(LogicalPlan input, List<SortKey> sortKeys) implements LogicalPlan {

    public record SortKey(Expression expression, Direction direction, NullOrder nullOrder) {
        public enum Direction { ASC, DESC }
        public enum NullOrder { NULLS_FIRST, NULLS_LAST }

        public SortKey(Expression expression, Direction direction) {
            this(expression, direction, direction == Direction.ASC ? NullOrder.NULLS_LAST : NullOrder.NULLS_FIRST);
        }
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
        return visitor.visitSort(this);
    }

    @Override
    public String toString() {
        return "Sort[" + sortKeys.stream()
                .map(k -> k.expression() + " " + k.direction())
                .toList() + "]";
    }
}
