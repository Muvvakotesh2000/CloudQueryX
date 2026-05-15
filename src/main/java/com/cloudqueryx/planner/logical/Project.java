package com.cloudqueryx.planner.logical;

import com.cloudqueryx.common.Column;
import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;

import java.util.ArrayList;
import java.util.List;

public record Project(LogicalPlan input, List<Expression> expressions, List<String> aliases)
        implements LogicalPlan {

    @Override
    public Schema getOutputSchema() {
        List<Column> columns = new ArrayList<>();
        Schema inputSchema = input.getOutputSchema();
        for (int i = 0; i < expressions.size(); i++) {
            String name = (aliases != null && i < aliases.size() && aliases.get(i) != null)
                    ? aliases.get(i)
                    : "col" + i;
            columns.add(new Column(name, expressions.get(i).getType(inputSchema)));
        }
        return new Schema(columns);
    }

    @Override
    public List<LogicalPlan> getChildren() {
        return List.of(input);
    }

    @Override
    public <R> R accept(LogicalPlanVisitor<R> visitor) {
        return visitor.visitProject(this);
    }

    @Override
    public String toString() {
        List<String> projections = new ArrayList<>();
        for (int i = 0; i < expressions.size(); i++) {
            String alias = (aliases != null && i < aliases.size()) ? aliases.get(i) : null;
            projections.add(alias != null ? expressions.get(i) + " AS " + alias : expressions.get(i).toString());
        }
        return "Project[" + String.join(", ", projections) + "]";
    }
}
