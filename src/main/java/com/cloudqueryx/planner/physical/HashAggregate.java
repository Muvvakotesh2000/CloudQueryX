package com.cloudqueryx.planner.physical;

import com.cloudqueryx.common.Column;
import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.AggregateExpression;
import com.cloudqueryx.expression.Expression;

import java.util.ArrayList;
import java.util.List;

public record HashAggregate(
        PhysicalPlan input,
        List<Expression> groupByKeys,
        List<AggregateExpression> aggregates,
        List<String> outputNames
) implements PhysicalPlan {

    @Override
    public Schema getOutputSchema() {
        List<Column> columns = new ArrayList<>();
        Schema inputSchema = input.getOutputSchema();
        for (int i = 0; i < groupByKeys.size(); i++) {
            String name = outputNames != null && i < outputNames.size() ? outputNames.get(i) : "group" + i;
            columns.add(new Column(name, groupByKeys.get(i).getType(inputSchema)));
        }
        for (int i = 0; i < aggregates.size(); i++) {
            int idx = groupByKeys.size() + i;
            String name = outputNames != null && idx < outputNames.size() ? outputNames.get(idx) : aggregates.get(i).toString();
            columns.add(new Column(name, aggregates.get(i).getType(inputSchema)));
        }
        return new Schema(columns);
    }

    @Override public List<PhysicalPlan> getChildren() { return List.of(input); }

    @Override
    public double estimatedCost() {
        return input.estimatedCost() + input.estimatedRowCount() * 2.0;
    }

    @Override
    public long estimatedRowCount() {
        if (groupByKeys.isEmpty()) return 1;
        return Math.max(1, input.estimatedRowCount() / 10);
    }

    @Override
    public <R> R accept(PhysicalPlanVisitor<R> visitor) {
        return visitor.visitHashAggregate(this);
    }

    @Override
    public String toString() {
        return "HashAggregate[groupBy=" + groupByKeys.size() + " keys, aggs=" + aggregates.size() + "]";
    }
}
