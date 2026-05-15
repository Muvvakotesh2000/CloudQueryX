package com.cloudqueryx.planner.logical;

public interface LogicalPlanVisitor<R> {
    R visitScan(Scan node);
    R visitFilter(Filter node);
    R visitProject(Project node);
    R visitJoin(Join node);
    R visitAggregate(Aggregate node);
    R visitSort(Sort node);
    R visitLimit(Limit node);
    R visitUnion(Union node);
    R visitValues(Values node);
}
