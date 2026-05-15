package com.cloudqueryx.optimizer.rules;

import com.cloudqueryx.expression.*;
import com.cloudqueryx.optimizer.OptimizerRule;
import com.cloudqueryx.planner.logical.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PredicatePushDown implements OptimizerRule {

    private static final Logger log = LoggerFactory.getLogger(PredicatePushDown.class);

    @Override
    public String name() {
        return "PredicatePushDown";
    }

    @Override
    public LogicalPlan apply(LogicalPlan plan) {
        return pushDown(plan);
    }

    private LogicalPlan pushDown(LogicalPlan plan) {
        if (plan instanceof Filter filter) {
            LogicalPlan optimizedInput = pushDown(filter.input());
            return pushFilterDown(filter.condition(), optimizedInput);
        }

        if (plan instanceof Join join) {
            return new Join(
                    pushDown(join.left()),
                    pushDown(join.right()),
                    join.joinType(),
                    join.condition()
            );
        }

        if (plan instanceof Project project) {
            return new Project(pushDown(project.input()), project.expressions(), project.aliases());
        }

        if (plan instanceof Aggregate agg) {
            return new Aggregate(pushDown(agg.input()), agg.groupByKeys(), agg.aggregates(), agg.outputNames());
        }

        if (plan instanceof Sort sort) {
            return new Sort(pushDown(sort.input()), sort.sortKeys());
        }

        if (plan instanceof Limit limit) {
            return new Limit(pushDown(limit.input()), limit.limit(), limit.offset());
        }

        return plan;
    }

    private LogicalPlan pushFilterDown(Expression predicate, LogicalPlan input) {
        List<Expression> conjuncts = LogicalExpression.flattenConjuncts(predicate);

        if (input instanceof Join join) {
            return pushFilterPastJoin(conjuncts, join);
        }

        if (input instanceof Project project) {
            return new Project(
                    pushFilterDown(predicate, project.input()),
                    project.expressions(),
                    project.aliases()
            );
        }

        return new Filter(input, predicate);
    }

    private LogicalPlan pushFilterPastJoin(List<Expression> predicates, Join join) {
        Set<String> leftTables = collectTableNames(join.left());
        Set<String> rightTables = collectTableNames(join.right());

        List<Expression> leftPredicates = new ArrayList<>();
        List<Expression> rightPredicates = new ArrayList<>();
        List<Expression> joinPredicates = new ArrayList<>();
        List<Expression> remainingPredicates = new ArrayList<>();

        for (Expression pred : predicates) {
            Set<String> referencedTables = pred.getReferencedColumns().stream()
                    .map(ColumnRef::tableName)
                    .filter(t -> t != null)
                    .collect(Collectors.toSet());

            if (referencedTables.isEmpty()) {
                remainingPredicates.add(pred);
            } else if (leftTables.containsAll(referencedTables)) {
                leftPredicates.add(pred);
                log.debug("Pushed predicate {} to left side of join", pred);
            } else if (rightTables.containsAll(referencedTables)) {
                if (join.joinType() == Join.JoinType.INNER || join.joinType() == Join.JoinType.RIGHT) {
                    rightPredicates.add(pred);
                    log.debug("Pushed predicate {} to right side of join", pred);
                } else {
                    remainingPredicates.add(pred);
                }
            } else {
                joinPredicates.add(pred);
            }
        }

        LogicalPlan left = join.left();
        if (!leftPredicates.isEmpty()) {
            left = new Filter(left, LogicalExpression.conjunct(leftPredicates));
        }

        LogicalPlan right = join.right();
        if (!rightPredicates.isEmpty()) {
            right = new Filter(right, LogicalExpression.conjunct(rightPredicates));
        }

        Expression joinCondition = join.condition();
        if (!joinPredicates.isEmpty()) {
            Expression combined = LogicalExpression.conjunct(joinPredicates);
            joinCondition = joinCondition != null
                    ? LogicalExpression.and(joinCondition, combined) : combined;
        }

        LogicalPlan result = new Join(left, right, join.joinType(), joinCondition);

        if (!remainingPredicates.isEmpty()) {
            result = new Filter(result, LogicalExpression.conjunct(remainingPredicates));
        }

        return result;
    }

    private Set<String> collectTableNames(LogicalPlan plan) {
        if (plan instanceof Scan scan) {
            return Set.of(scan.effectiveName());
        }
        return plan.getChildren().stream()
                .flatMap(child -> collectTableNames(child).stream())
                .collect(Collectors.toSet());
    }
}
