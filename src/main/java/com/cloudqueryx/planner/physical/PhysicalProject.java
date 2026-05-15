package com.cloudqueryx.planner.physical;

import com.cloudqueryx.common.Column;
import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;

import java.util.ArrayList;
import java.util.List;

public record PhysicalProject(PhysicalPlan input, List<Expression> expressions, List<String> aliases)
        implements PhysicalPlan {

    @Override
    public Schema getOutputSchema() {
        List<Column> columns = new ArrayList<>();
        Schema inputSchema = input.getOutputSchema();
        for (int i = 0; i < expressions.size(); i++) {
            String name = (aliases != null && i < aliases.size() && aliases.get(i) != null)
                    ? aliases.get(i) : "col" + i;
            columns.add(new Column(name, expressions.get(i).getType(inputSchema)));
        }
        return new Schema(columns);
    }

    @Override public List<PhysicalPlan> getChildren() { return List.of(input); }
    @Override public double estimatedCost() { return input.estimatedCost() + input.estimatedRowCount() * 0.01; }
    @Override public long estimatedRowCount() { return input.estimatedRowCount(); }

    @Override
    public <R> R accept(PhysicalPlanVisitor<R> visitor) {
        return visitor.visitPhysicalProject(this);
    }

    @Override
    public String toString() {
        return "Project[" + expressions.size() + " columns]";
    }
}
