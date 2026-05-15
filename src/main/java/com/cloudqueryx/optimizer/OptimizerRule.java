package com.cloudqueryx.optimizer;

import com.cloudqueryx.planner.logical.LogicalPlan;

public interface OptimizerRule {

    String name();

    LogicalPlan apply(LogicalPlan plan);

    default boolean isApplicable(LogicalPlan plan) {
        return true;
    }
}
