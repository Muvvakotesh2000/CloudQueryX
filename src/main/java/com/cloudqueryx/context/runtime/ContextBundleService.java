package com.cloudqueryx.context.runtime;

import com.cloudqueryx.repository.ContextBundleRepository;

import java.util.*;

public class ContextBundleService {
    private final ContextRetrievalService retrievalService;
    private final ContextBundleRepository bundleRepo;
    private final TokenBudgetOptimizer optimizer;
    private final ContextFormatterService formatterService;

    public ContextBundleService(ContextRetrievalService retrievalService,
                                ContextBundleRepository bundleRepo,
                                TokenBudgetOptimizer optimizer,
                                ContextFormatterService formatterService) {
        this.retrievalService = retrievalService;
        this.bundleRepo = bundleRepo;
        this.optimizer = optimizer;
        this.formatterService = formatterService;
    }

    public BundleBuildResult build(String databaseId, String userId, String query, String targetModel,
                                   int tokenBudget, String mode, boolean includeExplanations,
                                   List<String> sourceTypes, boolean includeMemories, boolean includeSources) {
        return build(databaseId, userId, query, targetModel, tokenBudget, mode, includeExplanations,
                sourceTypes, includeMemories, includeSources, false, false);
    }

    public BundleBuildResult build(String databaseId, String userId, String query, String targetModel,
                                   int tokenBudget, String mode, boolean includeExplanations,
                                   List<String> sourceTypes, boolean includeMemories, boolean includeSources,
                                   boolean includeGraph, boolean includeEvents) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query required");
        String bundleId = UUID.randomUUID().toString();
        ModelContextAdapter adapter = formatterService.adapter(targetModel);
        int effectiveBudget = Math.min(Math.max(256, tokenBudget), adapter.maxContextWindow());

        List<RetrievalResult> retrieved = retrievalService.retrieve(
                databaseId, userId, query, 30, sourceTypes, includeMemories, includeSources,
                includeGraph, includeEvents);
        List<TokenBudgetOptimizer.BundleCandidate> selected = optimizer.optimize(
                query, applyModePlanning(retrieved, mode), effectiveBudget);
        int estimatedTokens = selected.stream().mapToInt(TokenBudgetOptimizer.BundleCandidate::tokenEstimate).sum();
        String formatted = adapter.format(query, mode, selected);

        ContextBundleRepository.BundleRow bundle = new ContextBundleRepository.BundleRow(
                bundleId, databaseId, userId, query, adapter.modelKey(),
                mode != null ? mode : "general", effectiveBudget, estimatedTokens,
                "VALID", formatted, null
        );
        List<ContextBundleRepository.BundleItemRow> items = selected.stream()
                .map(candidate -> toItem(databaseId, bundleId, candidate))
                .toList();
        bundleRepo.save(bundle, items);

        return new BundleBuildResult(bundleId, query, adapter.modelKey(), effectiveBudget,
                estimatedTokens, adapter.estimatedCostUsd(estimatedTokens), "VALID",
                adapter.supportsPromptCaching(), formatted,
                toResponseItems(items, includeExplanations));
    }

    public Optional<ContextBundleRepository.BundleWithItems> get(String databaseId, String bundleId) {
        return bundleRepo.get(databaseId, bundleId);
    }

    private List<RetrievalResult> applyModePlanning(List<RetrievalResult> results, String mode) {
        String normalizedMode = mode == null || mode.isBlank()
                ? "general"
                : mode.toLowerCase(Locale.ROOT);
        List<RetrievalResult> planned = new ArrayList<>();
        for (RetrievalResult result : results) {
            double multiplier = modeMultiplier(result, normalizedMode);
            if (multiplier <= 0) continue;
            planned.add(withScore(
                    result,
                    clamp(result.finalScore() * multiplier),
                    multiplierReason(result.reason(), multiplier, normalizedMode)
            ));
        }
        planned.sort(Comparator.comparingDouble(RetrievalResult::finalScore).reversed());
        return planned;
    }

    private double modeMultiplier(RetrievalResult result, String mode) {
        String type = safeLower(result.type());
        String sourceType = safeLower(result.sourceType());
        String sourceName = safeLower(result.sourceName());
        boolean isLog = sourceType.contains("log") || sourceName.contains("log");
        boolean isConfig = sourceType.contains("config") || sourceName.endsWith(".yml") || sourceName.endsWith(".yaml")
                || sourceName.endsWith(".properties") || sourceName.endsWith(".env");

        return switch (mode) {
            case "debugging" -> {
                if (isLog) yield 1.45;
                if (isConfig) yield 1.30;
                if ("event".equals(type)) yield 1.20;
                yield 1.0;
            }
            case "coding" -> {
                if ("code".equals(sourceType)) yield 1.35;
                if (isConfig) yield 1.15;
                if (isLog) yield 0.72;
                yield 1.0;
            }
            case "planning" -> {
                if ("memory".equals(type) || "relationship".equals(type) || "event".equals(type)) yield 1.18;
                if (isLog) yield 0.55;
                yield 1.0;
            }
            case "research" -> {
                if ("document".equals(sourceType) || "note".equals(sourceType) || "entity".equals(type)) yield 1.18;
                if (isLog) yield 0.65;
                yield 1.0;
            }
            case "summary", "general" -> isLog ? 0.45 : 1.0;
            default -> isLog ? 0.55 : 1.0;
        };
    }

    private RetrievalResult withScore(RetrievalResult result, double finalScore, String reason) {
        return new RetrievalResult(
                result.type(),
                result.id(),
                result.sourceId(),
                result.chunkId(),
                result.memoryId(),
                result.sourceName(),
                result.sourceType(),
                result.content(),
                result.tokenEstimate(),
                result.vectorScore(),
                result.textScore(),
                result.freshnessScore(),
                finalScore,
                reason,
                result.metadata(),
                result.updatedAt()
        );
    }

    private String multiplierReason(String reason, double multiplier, String mode) {
        if (Math.abs(multiplier - 1.0) < 0.001) return reason;
        String direction = multiplier > 1.0 ? "boosted" : "lowered";
        return reason + "; " + direction + " for " + mode + " mode";
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }

    private ContextBundleRepository.BundleItemRow toItem(String databaseId, String bundleId,
                                                        TokenBudgetOptimizer.BundleCandidate candidate) {
        RetrievalResult result = candidate.result();
        String itemType = candidate.compressionDecision().equals("FULL")
                ? fullItemType(result.type())
                : "COMPRESSED_SUMMARY";
        return new ContextBundleRepository.BundleItemRow(
                UUID.randomUUID().toString(),
                databaseId,
                bundleId,
                itemType,
                result.sourceId(),
                result.chunkId(),
                result.memoryId(),
                candidate.content(),
                candidate.tokenEstimate(),
                result.finalScore(),
                result.reason(),
                result.type(),
                candidate.compressionDecision(),
                result.freshnessScore() >= 0.35 ? "VALID" : "STALE",
                null
        );
    }

    private String fullItemType(String retrievalType) {
        return switch (retrievalType) {
            case "MEMORY" -> "FULL_MEMORY";
            case "SOURCE_CHUNK" -> "FULL_CHUNK";
            case "ENTITY" -> "FULL_ENTITY";
            case "RELATIONSHIP" -> "FULL_RELATIONSHIP";
            case "EVENT" -> "FULL_EVENT";
            default -> "FULL_CONTEXT";
        };
    }

    private List<Map<String, Object>> toResponseItems(List<ContextBundleRepository.BundleItemRow> items,
                                                      boolean includeExplanations) {
        List<Map<String, Object>> response = new ArrayList<>();
        for (ContextBundleRepository.BundleItemRow item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", item.itemType());
            row.put("sourceId", item.sourceId());
            row.put("chunkId", item.chunkId());
            row.put("memoryId", item.memoryId());
            row.put("content", item.content());
            row.put("tokenEstimate", item.tokenEstimate());
            row.put("score", item.score());
            if (includeExplanations) {
                row.put("reason", item.reason());
                row.put("retrievalSource", item.retrievalSource());
                row.put("compressionDecision", item.compressionDecision());
                row.put("freshnessDecision", item.freshnessDecision());
            }
            response.add(row);
        }
        return response;
    }

    public record BundleBuildResult(
            String contextBundleId,
            String query,
            String targetModel,
            int tokenBudget,
            int estimatedTokens,
            double estimatedCost,
            String freshnessStatus,
            boolean promptCachingSupported,
            String formattedContext,
            List<Map<String, Object>> items
    ) {}
}
