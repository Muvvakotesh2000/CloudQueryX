package com.cloudqueryx.context.runtime;

import java.util.*;

public class TokenBudgetOptimizer {
    private final TokenEstimator tokenEstimator;
    private final ContextCompressor compressor;

    public TokenBudgetOptimizer(TokenEstimator tokenEstimator, ContextCompressor compressor) {
        this.tokenEstimator = tokenEstimator;
        this.compressor = compressor;
    }

    public List<BundleCandidate> optimize(String query, List<RetrievalResult> results, int tokenBudget) {
        int budget = Math.max(256, tokenBudget);
        int[] used = {0};
        List<BundleCandidate> selected = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();
        List<RetrievalResult> ranked = results.stream()
                .sorted(Comparator.comparingDouble(RetrievalResult::finalScore).reversed())
                .toList();

        Map<String, RetrievalResult> bestByType = new LinkedHashMap<>();
        for (RetrievalResult result : ranked) {
            bestByType.putIfAbsent(result.type(), result);
        }
        for (RetrievalResult result : bestByType.values()) {
            addCandidate(query, budget, used, selected, selectedIds, result);
        }

        for (RetrievalResult result : ranked) {
            if (used[0] >= budget) break;
            if (selectedIds.contains(result.id())) continue;
            addCandidate(query, budget, used, selected, selectedIds, result);
        }
        selected.sort(Comparator.comparingDouble((BundleCandidate c) -> c.result().finalScore()).reversed());
        return selected;
    }

    private void addCandidate(String query, int budget, int[] used, List<BundleCandidate> selected,
                              Set<String> selectedIds, RetrievalResult result) {
        if (used[0] >= budget) return;
        if (result.finalScore() < 0.10) return;

        String content = result.content();
        int tokens = result.tokenEstimate() > 0 ? result.tokenEstimate() : tokenEstimator.estimate(content);
        String decision = "FULL";

        if (used[0] + tokens > budget || (result.finalScore() < 0.45 && tokens > 180)) {
            int remaining = Math.max(80, budget - used[0]);
            content = compressor.compress(content, Math.min(remaining, Math.max(120, tokens / 2)), query);
            tokens = tokenEstimator.estimate(content);
            decision = "COMPRESSED_SUMMARY";
        }

        if (used[0] + tokens > budget) return;
        used[0] += tokens;
        selectedIds.add(result.id());
        selected.add(new BundleCandidate(result, content, tokens, decision));
    }

    public record BundleCandidate(
            RetrievalResult result,
            String content,
            int tokenEstimate,
            String compressionDecision
    ) {}
}
