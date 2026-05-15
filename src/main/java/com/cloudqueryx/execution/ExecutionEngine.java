package com.cloudqueryx.execution;

import com.cloudqueryx.common.*;
import com.cloudqueryx.execution.operators.*;
import com.cloudqueryx.planner.physical.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    public QueryResult execute(PhysicalPlan plan, QueryContext context) {
        log.info("Executing query {}...", context.getQueryId().substring(0, 8));
        log.debug("Physical plan:\n{}", plan.explain());

        long planningMs = context.getElapsedMs();
        Operator rootOperator = buildOperatorTree(plan, context);

        try {
            rootOperator.open();

            List<Row> results = new ArrayList<>();
            Row row;
            while ((row = rootOperator.next()) != null) {
                if (context.isCancelled()) {
                    log.info("Query {} cancelled", context.getQueryId().substring(0, 8));
                    break;
                }
                results.add(row);
                context.addRowsReturned(1);
            }

            long totalMs = context.getElapsedMs();
            log.info("Query {} completed: {} rows in {}ms (planning: {}ms, execution: {}ms)",
                    context.getQueryId().substring(0, 8), results.size(),
                    totalMs, planningMs, totalMs - planningMs);

            return new QueryResult(
                    plan.getOutputSchema(),
                    results,
                    context.getRowsScanned(),
                    context.getRowsReturned(),
                    planningMs,
                    totalMs - planningMs,
                    context.getBytesProcessed()
            );
        } finally {
            rootOperator.close();
        }
    }

    private Operator buildOperatorTree(PhysicalPlan plan, QueryContext context) {
        if (plan instanceof TableScan scan) {
            if ("__values__".equals(scan.tableName())) {
                return new EmptyOperator(scan.getOutputSchema());
            }
            return new ScanOperator(scan.tableName(), scan.outputSchema(),
                    scan.pushdownPredicate(), context);
        }

        if (plan instanceof PhysicalFilter filter) {
            Operator input = buildOperatorTree(filter.input(), context);
            return new FilterOperator(input, filter.condition());
        }

        if (plan instanceof PhysicalProject project) {
            Operator input = buildOperatorTree(project.input(), context);
            return new ProjectOperator(input, project.expressions(), project.aliases());
        }

        if (plan instanceof HashJoin hashJoin) {
            Operator buildSide = buildOperatorTree(hashJoin.buildSide(), context);
            Operator probeSide = buildOperatorTree(hashJoin.probeSide(), context);
            return new HashJoinOperator(buildSide, probeSide,
                    hashJoin.condition(), hashJoin.joinType());
        }

        if (plan instanceof NestedLoopJoin nlj) {
            Operator outer = buildOperatorTree(nlj.outer(), context);
            Operator inner = buildOperatorTree(nlj.inner(), context);
            return new HashJoinOperator(outer, inner, nlj.condition(), nlj.joinType());
        }

        if (plan instanceof SortMergeJoin smj) {
            Operator left = buildOperatorTree(smj.left(), context);
            Operator right = buildOperatorTree(smj.right(), context);
            return new HashJoinOperator(left, right, smj.condition(), smj.joinType());
        }

        if (plan instanceof HashAggregate agg) {
            Operator input = buildOperatorTree(agg.input(), context);
            return new AggregateOperator(input, agg.groupByKeys(),
                    agg.aggregates(), agg.outputNames());
        }

        if (plan instanceof PhysicalSort sort) {
            Operator input = buildOperatorTree(sort.input(), context);
            return new SortOperator(input, sort.sortKeys());
        }

        if (plan instanceof PhysicalLimit limit) {
            Operator input = buildOperatorTree(limit.input(), context);
            return new LimitOperator(input, limit.limit(), limit.offset());
        }

        if (plan instanceof Exchange exchange) {
            return buildOperatorTree(exchange.input(), context);
        }

        throw CloudQueryXException.executionError("Unknown physical plan node: " + plan.getClass().getSimpleName());
    }

    private static class EmptyOperator implements Operator {
        private final Schema schema;
        EmptyOperator(Schema schema) { this.schema = schema; }
        @Override public void open() {}
        @Override public Row next() { return null; }
        @Override public Schema getOutputSchema() { return schema; }
        @Override public void close() {}
    }

    public record QueryResult(
            Schema schema,
            List<Row> rows,
            long rowsScanned,
            long rowsReturned,
            long planningMs,
            long executionMs,
            long bytesProcessed
    ) {
        public long totalMs() {
            return planningMs + executionMs;
        }
    }
}
