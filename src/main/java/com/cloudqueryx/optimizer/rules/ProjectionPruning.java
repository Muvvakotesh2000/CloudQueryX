package com.cloudqueryx.optimizer.rules;

import com.cloudqueryx.expression.*;
import com.cloudqueryx.optimizer.OptimizerRule;
import com.cloudqueryx.planner.logical.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class ProjectionPruning implements OptimizerRule {

    private static final Logger log = LoggerFactory.getLogger(ProjectionPruning.class);

    @Override
    public String name() {
        return "ProjectionPruning";
    }

    @Override
    public LogicalPlan apply(LogicalPlan plan) {
        Set<String> requiredColumns = collectAllReferencedColumns(plan);
        return prune(plan, requiredColumns);
    }

    private LogicalPlan prune(LogicalPlan plan, Set<String> requiredColumns) {
        if (plan instanceof Project project) {
            List<Expression> prunedExprs = new ArrayList<>();
            List<String> prunedAliases = new ArrayList<>();

            for (int i = 0; i < project.expressions().size(); i++) {
                String alias = (project.aliases() != null && i < project.aliases().size())
                        ? project.aliases().get(i) : null;
                prunedExprs.add(project.expressions().get(i));
                prunedAliases.add(alias);
            }

            Set<String> childRequired = new HashSet<>(requiredColumns);
            for (Expression expr : prunedExprs) {
                for (ColumnRef ref : expr.getReferencedColumns()) {
                    childRequired.add(ref.columnName().toLowerCase());
                    if (ref.tableName() != null) {
                        childRequired.add(ref.qualifiedName().toLowerCase());
                    }
                }
            }

            LogicalPlan prunedInput = prune(project.input(), childRequired);
            return new Project(prunedInput, prunedExprs, prunedAliases);
        }

        if (plan instanceof Filter filter) {
            Set<String> expanded = new HashSet<>(requiredColumns);
            for (ColumnRef ref : filter.condition().getReferencedColumns()) {
                expanded.add(ref.columnName().toLowerCase());
                if (ref.tableName() != null) expanded.add(ref.qualifiedName().toLowerCase());
            }
            return new Filter(prune(filter.input(), expanded), filter.condition());
        }

        if (plan instanceof Join join) {
            Set<String> expanded = new HashSet<>(requiredColumns);
            if (join.condition() != null) {
                for (ColumnRef ref : join.condition().getReferencedColumns()) {
                    expanded.add(ref.columnName().toLowerCase());
                    if (ref.tableName() != null) expanded.add(ref.qualifiedName().toLowerCase());
                }
            }
            return new Join(
                    prune(join.left(), expanded),
                    prune(join.right(), expanded),
                    join.joinType(),
                    join.condition()
            );
        }

        if (plan instanceof Aggregate agg) {
            Set<String> expanded = new HashSet<>(requiredColumns);
            for (Expression key : agg.groupByKeys()) {
                for (ColumnRef ref : key.getReferencedColumns()) {
                    expanded.add(ref.columnName().toLowerCase());
                    if (ref.tableName() != null) expanded.add(ref.qualifiedName().toLowerCase());
                }
            }
            for (AggregateExpression aggExpr : agg.aggregates()) {
                for (ColumnRef ref : aggExpr.getReferencedColumns()) {
                    expanded.add(ref.columnName().toLowerCase());
                    if (ref.tableName() != null) expanded.add(ref.qualifiedName().toLowerCase());
                }
            }
            return new Aggregate(prune(agg.input(), expanded), agg.groupByKeys(), agg.aggregates(), agg.outputNames());
        }

        if (plan instanceof Sort sort) {
            Set<String> expanded = new HashSet<>(requiredColumns);
            for (Sort.SortKey key : sort.sortKeys()) {
                for (ColumnRef ref : key.expression().getReferencedColumns()) {
                    expanded.add(ref.columnName().toLowerCase());
                    if (ref.tableName() != null) expanded.add(ref.qualifiedName().toLowerCase());
                }
            }
            return new Sort(prune(sort.input(), expanded), sort.sortKeys());
        }

        if (plan instanceof Limit limit) {
            return new Limit(prune(limit.input(), requiredColumns), limit.limit(), limit.offset());
        }

        return plan;
    }

    private Set<String> collectAllReferencedColumns(LogicalPlan plan) {
        Set<String> columns = new HashSet<>();
        collectColumns(plan, columns);
        return columns;
    }

    private void collectColumns(LogicalPlan plan, Set<String> columns) {
        if (plan instanceof Project project) {
            for (Expression expr : project.expressions()) {
                addColumnRefs(expr, columns);
            }
        } else if (plan instanceof Filter filter) {
            addColumnRefs(filter.condition(), columns);
        } else if (plan instanceof Join join && join.condition() != null) {
            addColumnRefs(join.condition(), columns);
        } else if (plan instanceof Sort sort) {
            for (Sort.SortKey key : sort.sortKeys()) {
                addColumnRefs(key.expression(), columns);
            }
        } else if (plan instanceof Aggregate agg) {
            for (Expression key : agg.groupByKeys()) addColumnRefs(key, columns);
            for (AggregateExpression aggExpr : agg.aggregates()) addColumnRefs(aggExpr, columns);
        }

        for (LogicalPlan child : plan.getChildren()) {
            collectColumns(child, columns);
        }
    }

    private void addColumnRefs(Expression expr, Set<String> columns) {
        for (ColumnRef ref : expr.getReferencedColumns()) {
            columns.add(ref.columnName().toLowerCase());
            if (ref.tableName() != null) {
                columns.add(ref.qualifiedName().toLowerCase());
            }
        }
    }
}
