package com.cloudqueryx.planner.logical;

import com.cloudqueryx.common.Column;
import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.AggregateExpression;
import com.cloudqueryx.expression.Expression;

import java.util.ArrayList;
import java.util.List;

public record Aggregate(
        LogicalPlan input,
        List<Expression> groupByKeys,
        List<AggregateExpression> aggregates,
        List<String> outputNames
) implements LogicalPlan {

    @Override
    public Schema getOutputSchema() {
        List<Column> columns = new ArrayList<>();
        Schema inputSchema = input.getOutputSchema();

        for (int i = 0; i < groupByKeys.size(); i++) {
            String name = (outputNames != null && i < outputNames.size() && outputNames.get(i) != null)
                    ? outputNames.get(i)
                    : "group" + i;
            columns.add(new Column(name, groupByKeys.get(i).getType(inputSchema)));
        }

        for (int i = 0; i < aggregates.size(); i++) {
            int idx = groupByKeys.size() + i;
            String name = (outputNames != null && idx < outputNames.size() && outputNames.get(idx) != null)
                    ? outputNames.get(idx)
                    : aggregates.get(i).toString();
            columns.add(new Column(name, aggregates.get(i).getType(inputSchema)));
        }

        return new Schema(columns);
    }

    @Override
    public List<LogicalPlan> getChildren() {
        return List.of(input);
    }

    @Override
    public <R> R accept(LogicalPlanVisitor<R> visitor) {
        return visitor.visitAggregate(this);
    }

    @Override
    public String toString() {
        return "Aggregate[groupBy=" + groupByKeys + ", aggs=" + aggregates + "]";
    }
}
