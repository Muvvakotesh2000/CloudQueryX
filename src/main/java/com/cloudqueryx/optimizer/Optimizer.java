package com.cloudqueryx.optimizer;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.optimizer.rules.*;
import com.cloudqueryx.planner.logical.*;
import com.cloudqueryx.planner.physical.*;
import com.cloudqueryx.expression.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class Optimizer {

    private static final Logger log = LoggerFactory.getLogger(Optimizer.class);

    private final Catalog catalog;
    private final CostModel costModel;
    private final List<OptimizerRule> rules;

    public Optimizer(Catalog catalog) {
        this.catalog = catalog;
        this.costModel = new CostModel(catalog);
        this.rules = new ArrayList<>();
        rules.add(new PredicatePushDown());
        rules.add(new ProjectionPruning());
        rules.add(new JoinReorder(costModel));
    }

    public LogicalPlan optimizeLogical(LogicalPlan plan) {
        log.info("Optimizing logical plan...");
        log.debug("Original plan:\n{}", plan.explain());

        LogicalPlan optimized = plan;
        for (OptimizerRule rule : rules) {
            LogicalPlan before = optimized;
            optimized = rule.apply(optimized);
            if (optimized != before) {
                log.debug("Applied rule '{}', new plan:\n{}", rule.name(), optimized.explain());
            }
        }

        CostModel.CostEstimate originalCost = costModel.estimateCost(plan);
        CostModel.CostEstimate optimizedCost = costModel.estimateCost(optimized);
        double improvement = originalCost.cost() > 0
                ? (1.0 - optimizedCost.cost() / originalCost.cost()) * 100 : 0;

        log.info("Optimization complete. Cost: {} -> {} ({}% improvement)",
                String.format("%.1f", originalCost.cost()),
                String.format("%.1f", optimizedCost.cost()),
                String.format("%.1f", improvement));

        return optimized;
    }

    public PhysicalPlan createPhysicalPlan(LogicalPlan logicalPlan) {
        log.debug("Creating physical plan...");
        return logicalToPhysical(logicalPlan);
    }

    private PhysicalPlan logicalToPhysical(LogicalPlan plan) {
        if (plan instanceof Scan scan) {
            long rowCount;
            try {
                rowCount = catalog.getTableStatistics(scan.tableName()).getRowCount();
            } catch (Exception e) {
                rowCount = 10000;
            }
            return new TableScan(scan.tableName(), scan.alias(), scan.outputSchema(), rowCount);
        }

        if (plan instanceof Filter filter) {
            PhysicalPlan input = logicalToPhysical(filter.input());
            double selectivity = estimateFilterSelectivity(filter.condition());
            return new PhysicalFilter(input, filter.condition(), selectivity);
        }

        if (plan instanceof Project project) {
            PhysicalPlan input = logicalToPhysical(project.input());
            return new PhysicalProject(input, project.expressions(), project.aliases());
        }

        if (plan instanceof Join join) {
            PhysicalPlan left = logicalToPhysical(join.left());
            PhysicalPlan right = logicalToPhysical(join.right());
            return selectJoinStrategy(left, right, join.condition(), join.joinType());
        }

        if (plan instanceof Aggregate agg) {
            PhysicalPlan input = logicalToPhysical(agg.input());
            return new HashAggregate(input, agg.groupByKeys(), agg.aggregates(), agg.outputNames());
        }

        if (plan instanceof Sort sort) {
            PhysicalPlan input = logicalToPhysical(sort.input());
            return new PhysicalSort(input, sort.sortKeys());
        }

        if (plan instanceof Limit limit) {
            PhysicalPlan input = logicalToPhysical(limit.input());
            return new PhysicalLimit(input, limit.limit(), limit.offset());
        }

        if (plan instanceof Union union) {
            PhysicalPlan left = logicalToPhysical(union.left());
            PhysicalPlan right = logicalToPhysical(union.right());
            return new Exchange(left, Exchange.Type.GATHER);
        }

        if (plan instanceof Values) {
            return new TableScan("__values__", null,
                    plan.getOutputSchema(), 0);
        }

        throw new UnsupportedOperationException("Cannot convert plan: " + plan.getClass().getSimpleName());
    }

    private PhysicalPlan selectJoinStrategy(PhysicalPlan left, PhysicalPlan right,
                                             Expression condition, Join.JoinType joinType) {
        if (condition == null || joinType == Join.JoinType.CROSS) {
            log.debug("Using NestedLoopJoin (no equi-join condition)");
            return new NestedLoopJoin(left, right, condition, joinType);
        }

        boolean isEquiJoin = isEquiJoinCondition(condition);
        long leftRows = left.estimatedRowCount();
        long rightRows = right.estimatedRowCount();

        if (!isEquiJoin) {
            log.debug("Using NestedLoopJoin (non-equi condition)");
            return new NestedLoopJoin(left, right, condition, joinType);
        }

        PhysicalPlan buildSide = leftRows <= rightRows ? left : right;
        PhysicalPlan probeSide = leftRows <= rightRows ? right : left;

        if (leftRows > 100000 && rightRows > 100000) {
            double hashCost = new HashJoin(buildSide, probeSide, condition, joinType).estimatedCost();
            double smjCost = new SortMergeJoin(left, right, condition, joinType).estimatedCost();

            if (smjCost < hashCost) {
                log.debug("Using SortMergeJoin (cost: {} vs HashJoin: {})",
                        String.format("%.1f", smjCost), String.format("%.1f", hashCost));
                return new SortMergeJoin(left, right, condition, joinType);
            }
        }

        log.debug("Using HashJoin (build={} rows, probe={} rows)", buildSide.estimatedRowCount(),
                probeSide.estimatedRowCount());
        return new HashJoin(buildSide, probeSide, condition, joinType);
    }

    private boolean isEquiJoinCondition(Expression condition) {
        if (condition instanceof ComparisonExpression comp) {
            return comp.op() == ComparisonExpression.Op.EQUAL;
        }
        if (condition instanceof LogicalExpression le && le.op() == LogicalExpression.Op.AND) {
            return isEquiJoinCondition(le.left()) || isEquiJoinCondition(le.right());
        }
        return false;
    }

    private double estimateFilterSelectivity(Expression condition) {
        if (condition instanceof ComparisonExpression comp) {
            return switch (comp.op()) {
                case EQUAL -> 0.1;
                case NOT_EQUAL -> 0.9;
                default -> 0.3;
            };
        }
        if (condition instanceof LogicalExpression le) {
            double leftSel = estimateFilterSelectivity(le.left());
            double rightSel = estimateFilterSelectivity(le.right());
            return le.op() == LogicalExpression.Op.AND ? leftSel * rightSel : leftSel + rightSel - leftSel * rightSel;
        }
        return 0.3;
    }

    public CostModel getCostModel() {
        return costModel;
    }
}
