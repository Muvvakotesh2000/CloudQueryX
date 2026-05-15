package com.cloudqueryx.planner.logical;

import com.cloudqueryx.common.Schema;

import java.util.List;

public record Scan(String tableName, String alias, Schema outputSchema) implements LogicalPlan {

    public Scan(String tableName, Schema outputSchema) {
        this(tableName, tableName, outputSchema);
    }

    @Override
    public Schema getOutputSchema() {
        return outputSchema;
    }

    @Override
    public List<LogicalPlan> getChildren() {
        return List.of();
    }

    @Override
    public <R> R accept(LogicalPlanVisitor<R> visitor) {
        return visitor.visitScan(this);
    }

    public String effectiveName() {
        return alias != null ? alias : tableName;
    }

    @Override
    public String toString() {
        String name = alias != null && !alias.equals(tableName)
                ? tableName + " AS " + alias : tableName;
        return "Scan[" + name + "]";
    }
}
