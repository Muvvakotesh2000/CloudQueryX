package com.cloudqueryx.optimizer;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.catalog.TableStatistics;
import com.cloudqueryx.expression.*;
import com.cloudqueryx.planner.logical.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CostModel implements LogicalPlanVisitor<CostModel.CostEstimate> {

    private static final Logger log = LoggerFactory.getLogger(CostModel.class);

    private static final double SCAN_COST_PER_ROW = 1.0;
    private static final double FILTER_COST_PER_ROW = 0.1;
    private static final double PROJECT_COST_PER_ROW = 0.05;
    private static final double HASH_JOIN_BUILD_COST = 2.0;
    private static final double HASH_JOIN_PROBE_COST = 1.5;
    private static final double SORT_COST_FACTOR = 2.0;
    private static final double AGGREGATE_COST_PER_ROW = 1.5;
    private static final double NETWORK_COST_PER_ROW = 10.0;

    private final Catalog catalog;

    public CostModel(Catalog catalog) {
        this.catalog = catalog;
    }

    public CostEstimate estimateCost(LogicalPlan plan) {
        return plan.accept(this);
    }

    @Override
    public CostEstimate visitScan(Scan node) {
        long rowCount;
        try {
            TableStatistics stats = catalog.getTableStatistics(node.tableName());
            rowCount = stats.getRowCount();
        } catch (Exception e) {
            rowCount = 10000;
        }
        double cost = rowCount * SCAN_COST_PER_ROW;
        return new CostEstimate(cost, rowCount, rowCount * 100);
    }

    @Override
    public CostEstimate visitFilter(Filter node) {
        CostEstimate inputCost = node.input().accept(this);
        double selectivity = estimateSelectivity(node.condition(), inputCost.rowCount);
        long outputRows = Math.max(1, (long) (inputCost.rowCount * selectivity));
        double cost = inputCost.cost + inputCost.rowCount * FILTER_COST_PER_ROW;
        return new CostEstimate(cost, outputRows, outputRows * 100);
    }

    @Override
    public CostEstimate visitProject(Project node) {
        CostEstimate inputCost = node.input().accept(this);
        double cost = inputCost.cost + inputCost.rowCount * PROJECT_COST_PER_ROW;
        return new CostEstimate(cost, inputCost.rowCount, inputCost.rowCount * node.expressions().size() * 8);
    }

    @Override
    public CostEstimate visitJoin(Join node) {
        CostEstimate leftCost = node.left().accept(this);
        CostEstimate rightCost = node.right().accept(this);

        double joinSelectivity = estimateJoinSelectivity(node, leftCost, rightCost);
        long outputRows = Math.max(1, (long) (leftCost.rowCount * rightCost.rowCount * joinSelectivity));

        double buildCost = Math.min(leftCost.rowCount, rightCost.rowCount) * HASH_JOIN_BUILD_COST;
        double probeCost = Math.max(leftCost.rowCount, rightCost.rowCount) * HASH_JOIN_PROBE_COST;
        double cost = leftCost.cost + rightCost.cost + buildCost + probeCost;

        return new CostEstimate(cost, outputRows, outputRows * 200);
    }

    @Override
    public CostEstimate visitAggregate(Aggregate node) {
        CostEstimate inputCost = node.input().accept(this);
        long outputRows = node.groupByKeys().isEmpty() ? 1 :
                Math.max(1, inputCost.rowCount / 10);
        double cost = inputCost.cost + inputCost.rowCount * AGGREGATE_COST_PER_ROW;
        return new CostEstimate(cost, outputRows, outputRows * 100);
    }

    @Override
    public CostEstimate visitSort(Sort node) {
        CostEstimate inputCost = node.input().accept(this);
        double sortCost = inputCost.rowCount * Math.log(inputCost.rowCount + 1) * SORT_COST_FACTOR;
        double cost = inputCost.cost + sortCost;
        return new CostEstimate(cost, inputCost.rowCount, inputCost.sizeInBytes);
    }

    @Override
    public CostEstimate visitLimit(Limit node) {
        CostEstimate inputCost = node.input().accept(this);
        long outputRows = Math.min(node.limit(), inputCost.rowCount);
        return new CostEstimate(inputCost.cost, outputRows, outputRows * 100);
    }

    @Override
    public CostEstimate visitUnion(Union node) {
        CostEstimate leftCost = node.left().accept(this);
        CostEstimate rightCost = node.right().accept(this);
        long rows = leftCost.rowCount + rightCost.rowCount;
        return new CostEstimate(leftCost.cost + rightCost.cost, rows, rows * 100);
    }

    @Override
    public CostEstimate visitValues(Values node) {
        return new CostEstimate(node.rows().size(), node.rows().size(), node.rows().size() * 100);
    }

    // ─── Selectivity Estimation ─────────────────────────────────────────────

    private double estimateSelectivity(Expression expr, long totalRows) {
        if (expr instanceof ComparisonExpression comp) {
            return switch (comp.op()) {
                case EQUAL -> 1.0 / Math.max(totalRows, 10);
                case NOT_EQUAL -> 1.0 - (1.0 / Math.max(totalRows, 10));
                case LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL -> 0.3;
            };
        }
        if (expr instanceof LogicalExpression le) {
            double leftSel = estimateSelectivity(le.left(), totalRows);
            double rightSel = estimateSelectivity(le.right(), totalRows);
            return switch (le.op()) {
                case AND -> leftSel * rightSel;
                case OR -> leftSel + rightSel - leftSel * rightSel;
            };
        }
        if (expr instanceof NotExpression not) {
            return 1.0 - estimateSelectivity(not.operand(), totalRows);
        }
        if (expr instanceof IsNullExpression) return 0.05;
        if (expr instanceof LikeExpression) return 0.1;
        if (expr instanceof BetweenExpression) return 0.25;
        if (expr instanceof InExpression in) return Math.min(0.5, in.list().size() * 0.05);
        return 0.3;
    }

    private double estimateJoinSelectivity(Join join, CostEstimate leftCost, CostEstimate rightCost) {
        if (join.joinType() == Join.JoinType.CROSS || join.condition() == null) return 1.0;

        if (join.condition() instanceof ComparisonExpression comp
                && comp.op() == ComparisonExpression.Op.EQUAL) {
            long maxCard = Math.max(leftCost.rowCount, rightCost.rowCount);
            return 1.0 / Math.max(maxCard, 1);
        }
        return 0.1;
    }

    public record CostEstimate(double cost, long rowCount, long sizeInBytes) {
        @Override
        public String toString() {
            return String.format("Cost[%.1f, rows=%d, size=%d]", cost, rowCount, sizeInBytes);
        }
    }
}
