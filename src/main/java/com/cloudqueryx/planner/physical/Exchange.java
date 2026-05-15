package com.cloudqueryx.planner.physical;

import com.cloudqueryx.common.Schema;
import com.cloudqueryx.expression.Expression;

import java.util.List;

public record Exchange(PhysicalPlan input, Type type, List<Expression> partitionKeys, int partitionCount)
        implements PhysicalPlan {

    public enum Type {
        GATHER,
        REPARTITION,
        BROADCAST
    }

    public Exchange(PhysicalPlan input, Type type) {
        this(input, type, List.of(), 4);
    }

    @Override public Schema getOutputSchema() { return input.getOutputSchema(); }
    @Override public List<PhysicalPlan> getChildren() { return List.of(input); }

    @Override
    public double estimatedCost() {
        double networkCost = input.estimatedRowCount() * 10.0;
        return input.estimatedCost() + switch (type) {
            case GATHER -> networkCost * 0.5;
            case REPARTITION -> networkCost;
            case BROADCAST -> networkCost * partitionCount;
        };
    }

    @Override public long estimatedRowCount() { return input.estimatedRowCount(); }

    @Override
    public <R> R accept(PhysicalPlanVisitor<R> visitor) {
        return visitor.visitExchange(this);
    }

    @Override
    public String toString() {
        return "Exchange[" + type + ", partitions=" + partitionCount + "]";
    }
}
