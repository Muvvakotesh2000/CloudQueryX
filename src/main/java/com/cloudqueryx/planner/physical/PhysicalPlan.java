package com.cloudqueryx.planner.physical;

import com.cloudqueryx.common.Schema;

import java.util.List;

public interface PhysicalPlan {

    Schema getOutputSchema();

    List<PhysicalPlan> getChildren();

    double estimatedCost();

    long estimatedRowCount();

    <R> R accept(PhysicalPlanVisitor<R> visitor);

    default String treeString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ".repeat(indent))
          .append(toString())
          .append(String.format(" [cost=%.1f, rows=%d]", estimatedCost(), estimatedRowCount()))
          .append("\n");
        for (PhysicalPlan child : getChildren()) {
            sb.append(child.treeString(indent + 1));
        }
        return sb.toString();
    }

    default String explain() {
        return treeString(0);
    }
}
