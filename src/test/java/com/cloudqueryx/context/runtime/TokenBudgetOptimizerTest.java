package com.cloudqueryx.context.runtime;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TokenBudgetOptimizerTest {

    private final TokenEstimator estimator = new TokenEstimator();
    private final TokenBudgetOptimizer optimizer = new TokenBudgetOptimizer(
            estimator,
            new ExtractiveContextCompressor(estimator)
    );

    @Test
    void keepsHighRelevanceContextBeforeLowerScoredItems() {
        List<TokenBudgetOptimizer.BundleCandidate> selected = optimizer.optimize("deployment failure", List.of(
                result("low", "generic project notes", 0.12, 20),
                result("high", "deployment failure happens when DATABASE_URL is missing", 0.92, 20),
                result("medium", "deployment logs mention a missing environment variable", 0.55, 20)
        ), 80);

        assertEquals("high", selected.get(0).result().id());
        assertEquals("medium", selected.get(1).result().id());
        assertTrue(selected.stream().anyMatch(item -> item.result().id().equals("low")));
    }

    @Test
    void compressesLongMediumRelevanceContextToFitBudget() {
        String longContent = """
                Build started
                Deployment failed because DATABASE_URL was not configured
                Stack trace line 1
                Stack trace line 2
                Stack trace line 3
                Stack trace line 4
                Stack trace line 5
                Stack trace line 6
                Stack trace line 7
                Stack trace line 8
                Stack trace line 9
                Stack trace line 10
                """;

        List<TokenBudgetOptimizer.BundleCandidate> selected = optimizer.optimize("DATABASE_URL deployment", List.of(
                result("long", longContent, 0.40, 240)
        ), 120);

        assertEquals(1, selected.size());
        assertEquals("COMPRESSED_SUMMARY", selected.get(0).compressionDecision());
        assertTrue(selected.get(0).tokenEstimate() <= 120);
        assertTrue(selected.get(0).content().contains("DATABASE_URL"));
    }

    @Test
    void excludesVeryLowRelevanceContext() {
        List<TokenBudgetOptimizer.BundleCandidate> selected = optimizer.optimize("architecture", List.of(
                result("noise", "unrelated note", 0.05, 10)
        ), 1000);

        assertTrue(selected.isEmpty());
    }

    private RetrievalResult result(String id, String content, double score, int tokenEstimate) {
        return new RetrievalResult(
                "SOURCE_CHUNK",
                id,
                "source-" + id,
                "chunk-" + id,
                null,
                "test-source",
                "document",
                content,
                tokenEstimate,
                score,
                score,
                1.0,
                score,
                "test reason",
                Map.of(),
                Instant.now()
        );
    }
}
