package com.cloudqueryx.planner.logical;

import com.cloudqueryx.common.Schema;

import java.util.List;

public interface LogicalPlan {

    Schema getOutputSchema();

    List<LogicalPlan> getChildren();

    <R> R accept(LogicalPlanVisitor<R> visitor);

    default String treeString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ".repeat(indent)).append(toString()).append("\n");
        for (LogicalPlan child : getChildren()) {
            sb.append(child.treeString(indent + 1));
        }
        return sb.toString();
    }

    default String explain() {
        return treeString(0);
    }
}
