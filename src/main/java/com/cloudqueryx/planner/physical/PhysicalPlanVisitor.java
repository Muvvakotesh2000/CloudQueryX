package com.cloudqueryx.planner.physical;

public interface PhysicalPlanVisitor<R> {
    R visitTableScan(TableScan node);
    R visitPhysicalFilter(PhysicalFilter node);
    R visitPhysicalProject(PhysicalProject node);
    R visitHashJoin(HashJoin node);
    R visitSortMergeJoin(SortMergeJoin node);
    R visitNestedLoopJoin(NestedLoopJoin node);
    R visitHashAggregate(HashAggregate node);
    R visitPhysicalSort(PhysicalSort node);
    R visitPhysicalLimit(PhysicalLimit node);
    R visitExchange(Exchange node);
}
