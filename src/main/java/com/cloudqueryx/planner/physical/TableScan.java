package com.cloudqueryx.planner.physical;

import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;

import java.util.List;

public record TableScan(
        String tableName,
        String alias,
        Schema outputSchema,
        Expression pushdownPredicate,
        long estimatedRows,
        double costPerRow
) implements PhysicalPlan {

    public TableScan(String tableName, String alias, Schema outputSchema, long estimatedRows) {
        this(tableName, alias, outputSchema, null, estimatedRows, 1.0);
    }

    @Override public Schema getOutputSchema() { return outputSchema; }
    @Override public List<PhysicalPlan> getChildren() { return List.of(); }
    @Override public double estimatedCost() { return estimatedRows * costPerRow; }
    @Override public long estimatedRowCount() { return estimatedRows; }

    @Override
    public <R> R accept(PhysicalPlanVisitor<R> visitor) {
        return visitor.visitTableScan(this);
    }

    @Override
    public String toString() {
        String name = alias != null && !alias.equals(tableName) ? tableName + " AS " + alias : tableName;
        return "TableScan[" + name +
                (pushdownPredicate != null ? ", predicate=" + pushdownPredicate : "") + "]";
    }
}
