package com.cloudqueryx.planner.physical;

import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;
import com.cloudqueryx.planner.logical.Join;

import java.util.List;

public record HashJoin(
        PhysicalPlan buildSide,
        PhysicalPlan probeSide,
        Expression condition,
        Join.JoinType joinType
) implements PhysicalPlan {

    @Override
    public Schema getOutputSchema() {
        return buildSide.getOutputSchema().merge(probeSide.getOutputSchema());
    }

    @Override
    public List<PhysicalPlan> getChildren() {
        return List.of(buildSide, probeSide);
    }

    @Override
    public double estimatedCost() {
        double buildCost = buildSide.estimatedCost() + buildSide.estimatedRowCount() * 2.0;
        double probeCost = probeSide.estimatedCost() + probeSide.estimatedRowCount() * 1.5;
        return buildCost + probeCost;
    }

    @Override
    public long estimatedRowCount() {
        return switch (joinType) {
            case INNER -> Math.max(1, Math.min(buildSide.estimatedRowCount(), probeSide.estimatedRowCount()));
            case LEFT -> probeSide.estimatedRowCount();
            case RIGHT -> buildSide.estimatedRowCount();
            case FULL -> buildSide.estimatedRowCount() + probeSide.estimatedRowCount();
            case CROSS -> buildSide.estimatedRowCount() * probeSide.estimatedRowCount();
        };
    }

    @Override
    public <R> R accept(PhysicalPlanVisitor<R> visitor) {
        return visitor.visitHashJoin(this);
    }

    @Override
    public String toString() {
        return "HashJoin[" + joinType + ", on=" + condition + "]";
    }
}
