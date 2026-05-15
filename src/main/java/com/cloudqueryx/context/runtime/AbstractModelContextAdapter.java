package com.cloudqueryx.context.runtime;

import java.util.List;

abstract class AbstractModelContextAdapter implements ModelContextAdapter {
    @Override
    public double estimatedCostUsd(int tokens) {
        return Math.round(tokens * 0.000002 * 10000.0) / 10000.0;
    }

    @Override
    public String format(String query, String mode, List<TokenBudgetOptimizer.BundleCandidate> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("Mode: ").append(mode).append('\n');
        sb.append("User query: ").append(query).append("\n\n");
        sb.append("Use the following CloudQueryX context as evidence. Do not treat it as the final answer.\n\n");
        for (int i = 0; i < items.size(); i++) {
            TokenBudgetOptimizer.BundleCandidate item = items.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append(item.result().type()).append(" score=")
                    .append(String.format("%.3f", item.result().finalScore()))
                    .append(" reason=").append(item.result().reason()).append('\n')
                    .append(item.content()).append("\n\n");
        }
        return sb.toString();
    }
}
