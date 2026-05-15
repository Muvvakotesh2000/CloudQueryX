package com.cloudqueryx.planner.logical;

import com.cloudqueryx.common.Column;
import com.cloudqueryx.common.DataType;
import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;

import java.util.ArrayList;
import java.util.List;

public record Values(List<List<Expression>> rows, Schema schema) implements LogicalPlan {

    public Values(List<List<Expression>> rows) {
        this(rows, inferSchema(rows));
    }

    private static Schema inferSchema(List<List<Expression>> rows) {
        if (rows.isEmpty() || rows.get(0).isEmpty()) return new Schema(List.of());
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < rows.get(0).size(); i++) {
            columns.add(new Column("col" + i, DataType.VARCHAR));
        }
        return new Schema(columns);
    }

    @Override
    public Schema getOutputSchema() {
        return schema;
    }

    @Override
    public List<LogicalPlan> getChildren() {
        return List.of();
    }

    @Override
    public <R> R accept(LogicalPlanVisitor<R> visitor) {
        return visitor.visitValues(this);
    }

    @Override
    public String toString() {
        return "Values[" + rows.size() + " rows]";
    }
}
