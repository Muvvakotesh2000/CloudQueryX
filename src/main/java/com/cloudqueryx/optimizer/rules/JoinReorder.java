package com.cloudqueryx.optimizer.rules;

import com.cloudqueryx.expression.*;
import com.cloudqueryx.optimizer.CostModel;
import com.cloudqueryx.optimizer.OptimizerRule;
import com.cloudqueryx.planner.logical.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class JoinReorder implements OptimizerRule {

    private static final Logger log = LoggerFactory.getLogger(JoinReorder.class);
    private static final int MAX_TABLES_FOR_DP = 10;

    private final CostModel costModel;

    public JoinReorder(CostModel costModel) {
        this.costModel = costModel;
    }

    @Override
    public String name() {
        return "JoinReorder";
    }

    @Override
    public LogicalPlan apply(LogicalPlan plan) {
        return reorder(plan);
    }

    private LogicalPlan reorder(LogicalPlan plan) {
        if (plan instanceof Join join) {
            List<LogicalPlan> leaves = new ArrayList<>();
            List<Expression> joinConditions = new ArrayList<>();
            flattenJoins(join, leaves, joinConditions);

            if (leaves.size() <= 1) return plan;
            if (leaves.size() > MAX_TABLES_FOR_DP) {
                log.debug("Too many tables ({}) for DP join reordering, using greedy", leaves.size());
                return greedyJoinOrder(leaves, joinConditions);
            }

            return dpJoinOrder(leaves, joinConditions);
        }

        if (plan instanceof Filter filter) {
            return new Filter(reorder(filter.input()), filter.condition());
        }
        if (plan instanceof Project project) {
            return new Project(reorder(project.input()), project.expressions(), project.aliases());
        }
        if (plan instanceof Aggregate agg) {
            return new Aggregate(reorder(agg.input()), agg.groupByKeys(), agg.aggregates(), agg.outputNames());
        }
        if (plan instanceof Sort sort) {
            return new Sort(reorder(sort.input()), sort.sortKeys());
        }
        if (plan instanceof Limit limit) {
            return new Limit(reorder(limit.input()), limit.limit(), limit.offset());
        }

        return plan;
    }

    private void flattenJoins(LogicalPlan plan, List<LogicalPlan> leaves, List<Expression> conditions) {
        if (plan instanceof Join join && join.joinType() == Join.JoinType.INNER) {
            flattenJoins(join.left(), leaves, conditions);
            flattenJoins(join.right(), leaves, conditions);
            if (join.condition() != null) {
                conditions.addAll(LogicalExpression.flattenConjuncts(join.condition()));
            }
        } else {
            leaves.add(plan);
        }
    }

    private LogicalPlan dpJoinOrder(List<LogicalPlan> tables, List<Expression> conditions) {
        int n = tables.size();
        log.debug("DP join reorder with {} tables", n);

        Map<Integer, DpEntry> dp = new HashMap<>();

        for (int i = 0; i < n; i++) {
            int mask = 1 << i;
            CostModel.CostEstimate cost = costModel.estimateCost(tables.get(i));
            dp.put(mask, new DpEntry(tables.get(i), cost));
        }

        for (int size = 2; size <= n; size++) {
            for (int mask = 1; mask < (1 << n); mask++) {
                if (Integer.bitCount(mask) != size) continue;

                for (int sub = (mask - 1) & mask; sub > 0; sub = (sub - 1) & mask) {
                    int complement = mask ^ sub;
                    if (complement == 0 || sub > complement) continue;

                    DpEntry leftEntry = dp.get(sub);
                    DpEntry rightEntry = dp.get(complement);
                    if (leftEntry == null || rightEntry == null) continue;

                    Expression joinCond = findApplicableConditions(leftEntry.plan, rightEntry.plan, conditions);
                    Join candidateJoin = new Join(leftEntry.plan, rightEntry.plan, Join.JoinType.INNER, joinCond);
                    CostModel.CostEstimate candidateCost = costModel.estimateCost(candidateJoin);

                    DpEntry existing = dp.get(mask);
                    if (existing == null || candidateCost.cost() < existing.cost.cost()) {
                        dp.put(mask, new DpEntry(candidateJoin, candidateCost));
                        log.debug("  Better join order found for mask={}: cost={}", mask, candidateCost.cost());
                    }
                }
            }
        }

        int fullMask = (1 << n) - 1;
        DpEntry best = dp.get(fullMask);
        if (best == null) {
            return greedyJoinOrder(tables, conditions);
        }

        log.info("DP join reorder selected plan with cost: {}", best.cost.cost());
        return best.plan;
    }

    private LogicalPlan greedyJoinOrder(List<LogicalPlan> tables, List<Expression> conditions) {
        List<LogicalPlan> remaining = new ArrayList<>(tables);
        LogicalPlan result = remaining.remove(0);

        while (!remaining.isEmpty()) {
            int bestIdx = 0;
            double bestCost = Double.MAX_VALUE;

            for (int i = 0; i < remaining.size(); i++) {
                Expression cond = findApplicableConditions(result, remaining.get(i), conditions);
                Join candidate = new Join(result, remaining.get(i), Join.JoinType.INNER, cond);
                double cost = costModel.estimateCost(candidate).cost();
                if (cost < bestCost) {
                    bestCost = cost;
                    bestIdx = i;
                }
            }

            LogicalPlan next = remaining.remove(bestIdx);
            Expression cond = findApplicableConditions(result, next, conditions);
            result = new Join(result, next, Join.JoinType.INNER, cond);
        }

        return result;
    }

    private Expression findApplicableConditions(LogicalPlan left, LogicalPlan right, List<Expression> allConditions) {
        Set<String> leftTables = collectTableNames(left);
        Set<String> rightTables = collectTableNames(right);

        List<Expression> applicable = new ArrayList<>();
        for (Expression cond : allConditions) {
            Set<String> refs = new HashSet<>();
            for (ColumnRef col : cond.getReferencedColumns()) {
                if (col.tableName() != null) refs.add(col.tableName().toLowerCase());
            }
            boolean touchesLeft = refs.stream().anyMatch(leftTables::contains);
            boolean touchesRight = refs.stream().anyMatch(rightTables::contains);
            boolean touchesOnlyLeftRight = leftTables.containsAll(refs) || rightTables.containsAll(refs)
                    || refs.stream().allMatch(r -> leftTables.contains(r) || rightTables.contains(r));

            if (touchesLeft && touchesRight && touchesOnlyLeftRight) {
                applicable.add(cond);
            }
        }

        if (applicable.isEmpty()) return null;
        return LogicalExpression.conjunct(applicable);
    }

    private Set<String> collectTableNames(LogicalPlan plan) {
        Set<String> names = new HashSet<>();
        collectTableNamesRecursive(plan, names);
        return names;
    }

    private void collectTableNamesRecursive(LogicalPlan plan, Set<String> names) {
        if (plan instanceof Scan scan) {
            names.add(scan.effectiveName().toLowerCase());
        }
        for (LogicalPlan child : plan.getChildren()) {
            collectTableNamesRecursive(child, names);
        }
    }

    private record DpEntry(LogicalPlan plan, CostModel.CostEstimate cost) {}
}
