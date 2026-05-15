package com.cloudqueryx.context.runtime;

import java.util.*;

public class ExtractiveContextCompressor implements ContextCompressor {
    private final TokenEstimator tokenEstimator;

    public ExtractiveContextCompressor(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public String compress(String content, int maxTokens, String query) {
        if (content == null || content.isBlank()) return "";
        if (tokenEstimator.estimate(content) <= maxTokens) return content;
        Set<String> queryTerms = terms(query);
        String[] lines = content.split("\\R");
        List<String> selected = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (selected.isEmpty() || overlaps(line, queryTerms)) {
                selected.add(line.trim());
            }
            String candidate = String.join("\n", selected);
            if (tokenEstimator.estimate(candidate) >= maxTokens) break;
        }
        String compressed = String.join("\n", selected);
        if (compressed.isBlank()) compressed = content.substring(0, Math.min(content.length(), maxTokens * 4));
        while (tokenEstimator.estimate(compressed) > maxTokens && compressed.length() > 80) {
            compressed = compressed.substring(0, (int) (compressed.length() * 0.85));
        }
        return compressed.strip() + "\n[compressed extract]";
    }

    private boolean overlaps(String line, Set<String> queryTerms) {
        if (queryTerms.isEmpty()) return true;
        Set<String> lineTerms = terms(line);
        return queryTerms.stream().anyMatch(lineTerms::contains);
    }

    private Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null) return terms;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 2) terms.add(token);
        }
        return terms;
    }
}
