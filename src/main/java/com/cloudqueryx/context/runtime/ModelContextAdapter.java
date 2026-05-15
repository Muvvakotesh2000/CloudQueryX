package com.cloudqueryx.context.runtime;

import java.util.List;

public interface ModelContextAdapter {
    String modelKey();
    int maxContextWindow();
    boolean supportsPromptCaching();
    double estimatedCostUsd(int tokens);
    String format(String query, String mode, List<TokenBudgetOptimizer.BundleCandidate> items);
}
