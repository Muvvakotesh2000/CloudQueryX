package com.cloudqueryx.context.runtime;

import com.cloudqueryx.repository.ContextBundleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ContextBundleModePlanningTest {

    @Test
    void generalModeLowersLogsAndDebuggingModeBoostsLogs() {
        FakeRetrievalService retrieval = new FakeRetrievalService();
        CapturingBundleRepository bundleRepo = new CapturingBundleRepository();
        ContextBundleService service = new ContextBundleService(
                retrieval,
                bundleRepo,
                new TokenBudgetOptimizer(new TokenEstimator(), new ExtractiveContextCompressor(new TokenEstimator())),
                new ContextFormatterService()
        );

        ContextBundleService.BundleBuildResult general = service.build(
                "db", "user", "Explain architecture", "medium-context-model",
                4000, "general", true, List.of(), true, true, false, false);

        ContextBundleService.BundleBuildResult debugging = service.build(
                "db", "user", "Explain architecture", "medium-context-model",
                4000, "debugging", true, List.of(), true, true, false, false);

        double generalLogScore = scoreFor(general, "deployment-log.txt");
        double debuggingLogScore = scoreFor(debugging, "deployment-log.txt");

        assertTrue(generalLogScore < 0.4, "general mode should lower log score");
        assertTrue(debuggingLogScore > 0.9, "debugging mode should boost log score");
        assertTrue(general.items().stream().anyMatch(item ->
                String.valueOf(item.get("reason")).contains("lowered for general mode")));
        assertTrue(debugging.items().stream().anyMatch(item ->
                String.valueOf(item.get("reason")).contains("boosted for debugging mode")));
    }

    private double scoreFor(ContextBundleService.BundleBuildResult result, String contentNeedle) {
        return result.items().stream()
                .filter(item -> String.valueOf(item.get("content")).contains(contentNeedle))
                .mapToDouble(item -> ((Number) item.get("score")).doubleValue())
                .findFirst()
                .orElseThrow();
    }

    private static class FakeRetrievalService extends ContextRetrievalService {
        FakeRetrievalService() {
            super(null, null, null, new TokenEstimator());
        }

        @Override
        public List<RetrievalResult> retrieve(String databaseId, String userId, String query, int topK,
                                              List<String> sourceTypes, boolean includeMemories,
                                              boolean includeSources, boolean includeGraph,
                                              boolean includeEvents) {
            return List.of(
                    result("doc", "SOURCE_CHUNK", "document", "architecture-note.md",
                            "architecture-note.md CloudQueryX uses a Java API and context runtime.", 0.72),
                    result("log", "SOURCE_CHUNK", "log", "deployment-log.txt",
                            "deployment-log.txt CloudQueryX started and returned formattedContext successfully.", 0.72)
            );
        }

        private RetrievalResult result(String id, String type, String sourceType, String sourceName,
                                       String content, double score) {
            return new RetrievalResult(type, id, "source-" + id, "chunk-" + id, null,
                    sourceName, sourceType, content, 20, score, score, 1.0, score,
                    "Selected test item", java.util.Map.of(), java.time.Instant.now());
        }
    }

    private static class CapturingBundleRepository extends ContextBundleRepository {
        CapturingBundleRepository() {
            super(null);
        }

        @Override
        public void save(BundleRow bundle, List<BundleItemRow> items) {
        }

        @Override
        public Optional<BundleWithItems> get(String databaseId, String bundleId) {
            return Optional.empty();
        }
    }
}
