package com.cloudqueryx.context;

import com.cloudqueryx.embedding.EmbeddingService;
import com.cloudqueryx.repository.*;
import com.cloudqueryx.webhook.WebhookDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class ContextEngine {

    private static final Logger log = LoggerFactory.getLogger(ContextEngine.class);
    private static final String DEFAULT_NAMESPACE = "default";
    private static final double RRF_K = 60.0; // Reciprocal Rank Fusion constant
    private static final Map<String, Double> SOURCE_WEIGHTS = Map.of(
            "fulltext", 2.2,
            "memory", 1.6,
            "vector", 1.0,
            "semantic", 0.65,
            "events", 0.45
    );

    private final String databaseId;
    private final VectorRepository vectorRepo;
    private final MemoryRepository memoryRepo;
    private final GraphRepository graphRepo;
    private final EventRepository eventRepo;
    private final EmbeddingService embeddingService;
    private final WebhookDispatcher webhookDispatcher;

    public ContextEngine(String databaseId, VectorRepository vectorRepo,
                         MemoryRepository memoryRepo, GraphRepository graphRepo,
                         EventRepository eventRepo) {
        this(databaseId, vectorRepo, memoryRepo, graphRepo, eventRepo, null, null);
    }

    public ContextEngine(String databaseId, VectorRepository vectorRepo,
                         MemoryRepository memoryRepo, GraphRepository graphRepo,
                         EventRepository eventRepo, EmbeddingService embeddingService) {
        this(databaseId, vectorRepo, memoryRepo, graphRepo, eventRepo, embeddingService, null);
    }

    public ContextEngine(String databaseId, VectorRepository vectorRepo,
                         MemoryRepository memoryRepo, GraphRepository graphRepo,
                         EventRepository eventRepo, EmbeddingService embeddingService,
                         WebhookDispatcher webhookDispatcher) {
        this.databaseId = databaseId;
        this.vectorRepo = vectorRepo;
        this.memoryRepo = memoryRepo;
        this.graphRepo = graphRepo;
        this.eventRepo = eventRepo;
        this.embeddingService = embeddingService;
        this.webhookDispatcher = webhookDispatcher;
    }

    // ─── STORE ─────────────────────────────────────────────────────────

    public Map<String, Object> store(String userId, Map<String, Object> input) {
        String kind = stringVal(input, "kind", stringVal(input, "type", "memory")).toLowerCase();
        Map<String, Object> result = switch (kind) {
            case "memory", "conversation", "fact", "preference", "feedback", "decision",
                 "working", "semantic", "episodic", "procedural" ->
                    storeMemory(userId, input, kind);
            case "vector", "embedding" -> storeVector(input);
            case "entity", "semantic_entity" -> storeEntity(userId, input);
            case "relationship", "semantic_relationship" -> storeRelationship(input);
            case "event", "behavior_event" -> storeEvent(userId, input);
            default -> throw new IllegalArgumentException("Unsupported context store kind: " + kind);
        };
        fireWebhook("context.stored", result);
        return result;
    }

    // ─── RECALL (RRF multi-signal fusion) ──────────────────────────────

    public List<ContextRecallResult> recall(String userId, Map<String, Object> input) {
        float[] embedding = embeddingVal(input.get("embedding"));
        if (embedding == null && embeddingService != null) {
            String query = stringVal(input, "query", stringVal(input, "content", null));
            if (query != null && !query.isBlank()) {
                embedding = embeddingService.embed(query);
            }
        }
        if (embedding == null) {
            throw new IllegalArgumentException("Provide 'query' text (auto-embedded) or 'embedding' vector");
        }

        String namespace = stringVal(input, "namespace", DEFAULT_NAMESPACE);
        int topK = intVal(input, "topK", intVal(input, "top_k", 10));
        boolean includeRelated = boolVal(input, "includeRelated", boolVal(input, "include_related", false));
        int maxDepth = intVal(input, "maxDepth", intVal(input, "max_depth", 2));
        String scopeFilter = stringVal(input, "scope", null);
        Instant asOf = instantVal(input, "asOf");

        // Tunable weights per RECALL call
        Map<String, Object> weights = mapVal(input.get("weights"));
        double wSimilarity = doubleFromMap(weights, "similarity", 0.4);
        double wRecency = doubleFromMap(weights, "recency", 0.2);
        double wImportance = doubleFromMap(weights, "importance", 0.3);
        double wConfidence = doubleFromMap(weights, "confidence", 0.1);

        List<String> sourceFilter = stringList(input.get("sources"));
        if (sourceFilter.isEmpty()) {
            sourceFilter = List.of("memory", "fulltext", "vector", "semantic", "events");
        }

        // Collect ranked lists from each source
        Map<String, List<ScoredItem>> rankedLists = new LinkedHashMap<>();

        if (sourceFilter.contains("memory")) {
            String memoryTypeFilter = stringVal(input, "memoryType", null);
            List<MemoryRepository.MemorySearchRow> memResults = memoryRepo.searchSimilar(
                    databaseId, namespace, embedding, topK * 2, memoryTypeFilter, userId,
                    scopeFilter, asOf);

            List<ScoredItem> memItems = new ArrayList<>();
            for (MemoryRepository.MemorySearchRow msr : memResults) {
                MemoryRepository.MemoryRow m = msr.row();
                memoryRepo.reinforceAccess(databaseId, m.id());

                double relevance = msr.similarity() * wSimilarity
                        + m.recency() * wRecency
                        + m.importance() * wImportance
                        + m.confidence() * wConfidence;

                String explanation = String.format(
                        "similarity=%.3f, recency=%.2f, importance=%.2f, confidence=%.2f → relevance=%.3f",
                        msr.similarity(), m.recency(), m.importance(), m.confidence(), relevance);

                memItems.add(new ScoredItem(new ContextRecallResult(
                        m.id(), "memory", m.memoryType(),
                        m.content() != null ? m.content() : "",
                        relevance, m.metadata() != null ? m.metadata() : Map.of(), explanation
                ), relevance));
            }
            rankedLists.put("memory", memItems);
        }

        if (sourceFilter.contains("fulltext") || sourceFilter.contains("memory")) {
            String queryText = stringVal(input, "query", stringVal(input, "content", null));
            if (queryText != null && !queryText.isBlank()) {
                String memoryTypeFilter = stringVal(input, "memoryType", null);
                List<MemoryRepository.MemorySearchRow> ftResults = memoryRepo.searchFullText(
                        databaseId, namespace, queryText, topK, memoryTypeFilter, userId);

                List<ScoredItem> ftItems = new ArrayList<>();
                for (MemoryRepository.MemorySearchRow ftr : ftResults) {
                    MemoryRepository.MemoryRow m = ftr.row();
                    ftItems.add(new ScoredItem(new ContextRecallResult(
                            m.id(), "fulltext", m.memoryType(),
                            m.content() != null ? m.content() : "",
                            ftr.similarity(),
                            m.metadata() != null ? m.metadata() : Map.of(),
                            String.format("Full-text BM25 match (rank=%.4f)", ftr.similarity())
                    ), ftr.similarity()));
                }
                if (!ftItems.isEmpty()) {
                    rankedLists.put("fulltext", ftItems);
                }
            }
        }

        if (sourceFilter.contains("vector")) {
            List<VectorRepository.VectorRow> vecResults = vectorRepo.search(
                    databaseId, namespace, embedding, topK);

            List<ScoredItem> vecItems = vecResults.stream()
                    .map(vr -> {
                        Map<String, Object> vrMeta = vr.metadata() != null ? vr.metadata() : Map.of();
                        return new ScoredItem(new ContextRecallResult(
                                vr.id(), "vector",
                                String.valueOf(vrMeta.getOrDefault("type", "VECTOR")),
                                vr.content() != null ? vr.content() : "", vr.score(), vrMeta,
                                String.format("Vector cosine similarity %.3f in '%s'", vr.score(), namespace)
                        ), vr.score());
                    })
                    .toList();
            rankedLists.put("vector", vecItems);
        }

        if (sourceFilter.contains("semantic")) {
            List<GraphRepository.EntitySearchRow> entityResults = graphRepo.searchEntities(
                    databaseId, embedding, topK);

            List<ScoredItem> semItems = new ArrayList<>();
            for (GraphRepository.EntitySearchRow esr : entityResults) {
                GraphRepository.EntityRow e = esr.entity();
                if (userId != null && e.userId() != null && !userId.equals(e.userId())) continue;

                Map<String, Object> meta = new HashMap<>(e.attributes() != null ? e.attributes() : Map.of());
                meta.put("entityType", e.entityType());
                meta.put("confidence", e.confidence());

                semItems.add(new ScoredItem(new ContextRecallResult(
                        e.id(), "semantic", e.entityType(),
                        e.description() != null ? e.description() : e.name(),
                        esr.similarity(), meta,
                        "Semantic entity matched by embedding similarity"
                ), esr.similarity()));

                if (includeRelated) {
                    List<GraphRepository.EntityRow> related = graphRepo.findRelated(
                            databaseId, e.id(), null, maxDepth);
                    for (GraphRepository.EntityRow rel : related) {
                        if (userId != null && rel.userId() != null && !userId.equals(rel.userId())) continue;
                        Map<String, Object> relMeta = new HashMap<>(rel.attributes() != null ? rel.attributes() : Map.of());
                        relMeta.put("entityType", rel.entityType());
                        double score = Math.max(0.1, 0.65 / Math.max(1, maxDepth));
                        semItems.add(new ScoredItem(new ContextRecallResult(
                                rel.id(), "semantic", rel.entityType(),
                                rel.description() != null ? rel.description() : rel.name(),
                                score, relMeta,
                                "Related to entity '" + (e.name() != null ? e.name() : "") + "'"
                        ), score));
                    }
                }
            }
            rankedLists.put("semantic", semItems);
        }

        if (sourceFilter.contains("events")) {
            List<EventRepository.EventRow> events = eventRepo.queryByUser(
                    databaseId, userId, Math.min(topK, 25));

            List<ScoredItem> evtItems = events.stream()
                    .map(ev -> new ScoredItem(new ContextRecallResult(
                            ev.id(), "event",
                            ev.eventType() != null ? ev.eventType() : "EVENT",
                            ev.action() != null ? ev.action() : "",
                            0.45, ev.properties() != null ? ev.properties() : Map.of(),
                            "Recent behavior event for real-time learning context"
                    ), 0.45))
                    .toList();
            rankedLists.put("events", evtItems);
        }

        // Apply Reciprocal Rank Fusion across all sources
        String queryText = stringVal(input, "query", stringVal(input, "content", null));
        return applyRRF(rankedLists, topK, queryText);
    }

    // ─── RELATE ────────────────────────────────────────────────────────

    public Map<String, Object> relate(String userId, Map<String, Object> input) {
        String sourceEntityId = requiredStr(input, "sourceEntityId");
        String targetEntityId = requiredStr(input, "targetEntityId");

        Optional<GraphRepository.EntityRow> source = graphRepo.getEntity(databaseId, sourceEntityId);
        Optional<GraphRepository.EntityRow> target = graphRepo.getEntity(databaseId, targetEntityId);

        if (source.isEmpty() || target.isEmpty()) {
            throw new IllegalArgumentException("Both entities must exist");
        }
        if (!userId.equals(source.get().userId()) || !userId.equals(target.get().userId())) {
            throw new IllegalArgumentException("Both entities must belong to the current user");
        }

        String relId = stringVal(input, "id", UUID.randomUUID().toString());
        GraphRepository.RelationshipRow rel = new GraphRepository.RelationshipRow(
                relId, sourceEntityId, targetEntityId,
                stringVal(input, "relationshipType", "RELATED_TO"),
                mapVal(input.get("attributes")),
                doubleVal(input, "weight", 1.0),
                doubleVal(input, "confidence", 1.0),
                stringVal(input, "source", "context-api"),
                null, null, null
        );
        graphRepo.upsertRelationship(databaseId, rel);
        return Map.of("id", relId, "relationshipType", rel.relationshipType());
    }

    // ─── LEARN ─────────────────────────────────────────────────────────

    public Map<String, Object> learn(String userId, Map<String, Object> input) {
        String eventId = stringVal(input, "id", UUID.randomUUID().toString());
        EventRepository.EventRow event = new EventRepository.EventRow(
                eventId, userId,
                stringVal(input, "eventType", stringVal(input, "type", "CONTEXT_EVENT")),
                stringVal(input, "action", stringVal(input, "content", null)),
                mapVal(input.get("properties")),
                stringVal(input, "sessionId", null),
                null
        );
        eventRepo.insert(databaseId, event);
        return Map.of("id", eventId, "eventType", event.eventType());
    }

    // ─── FORGET (bitemporal: expire, don't delete) ─────────────────────

    public Map<String, Object> forget(String userId, Map<String, Object> input) {
        String id = requiredStr(input, "id");
        boolean memoryDeleted = false;
        boolean entityDeleted = false;

        Optional<MemoryRepository.MemoryRow> memory = memoryRepo.getById(databaseId, id);
        if (memory.isPresent() && Objects.equals(userId, memory.get().userId())) {
            memoryDeleted = memoryRepo.delete(databaseId, userId, id);
        }

        Optional<GraphRepository.EntityRow> entity = graphRepo.getEntity(databaseId, id);
        if (entity.isPresent() && Objects.equals(userId, entity.get().userId())) {
            entityDeleted = graphRepo.deleteEntity(databaseId, id);
        }

        Map<String, Object> result = Map.of(
                "id", id,
                "forgotten", memoryDeleted || entityDeleted,
                "memory", memoryDeleted,
                "deleted", memoryDeleted || entityDeleted,
                "semantic", entityDeleted
        );
        if (memoryDeleted || entityDeleted) {
            fireWebhook("context.forgotten", result);
        }
        return result;
    }

    // ─── Reciprocal Rank Fusion ────────────────────────────────────────

    private List<ContextRecallResult> applyRRF(Map<String, List<ScoredItem>> rankedLists, int topK, String queryText) {
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, ContextRecallResult> resultMap = new LinkedHashMap<>();

        for (Map.Entry<String, List<ScoredItem>> entry : rankedLists.entrySet()) {
            List<ScoredItem> items = new ArrayList<>(entry.getValue());
            items.sort(Comparator.comparingDouble(ScoredItem::score).reversed());
            double sourceWeight = SOURCE_WEIGHTS.getOrDefault(entry.getKey(), 1.0);

            for (int rank = 0; rank < items.size(); rank++) {
                ScoredItem item = items.get(rank);
                String key = resultKey(item.result);
                double scoreWeight = normalizedScore(entry.getKey(), item.score());
                double lexicalBoost = lexicalBoost(queryText, item.result.content());
                double rrfScore = sourceWeight * scoreWeight * lexicalBoost / (RRF_K + rank + 1);
                rrfScores.merge(key, rrfScore, Double::sum);
                resultMap.merge(key, item.result, this::preferDirectResult);
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    ContextRecallResult original = resultMap.get(e.getKey());
                    return new ContextRecallResult(
                            original.id(), original.source(), original.type(),
                            original.content(), e.getValue(), original.metadata(),
                            original.explanation() + " [RRF=" + String.format("%.4f", e.getValue()) + "]"
                    );
                })
                .toList();
    }

    private String resultKey(ContextRecallResult result) {
        if (result.id() != null && !result.id().isBlank()) return result.id();
        return result.source() + ":" + result.type() + ":" + result.content();
    }

    private ContextRecallResult preferDirectResult(ContextRecallResult existing, ContextRecallResult candidate) {
        if ("fulltext".equals(candidate.source()) && !"fulltext".equals(existing.source())) return candidate;
        if ("memory".equals(candidate.source()) && "semantic".equals(existing.source())) return candidate;
        if (candidate.score() > existing.score()) return candidate;
        return existing;
    }

    private double normalizedScore(String source, double score) {
        if ("fulltext".equals(source) || "memory".equals(source)) return 1.0;
        if (Double.isNaN(score) || Double.isInfinite(score)) return 0.25;
        return Math.max(0.05, Math.min(1.0, Math.max(0.0, score)));
    }

    private double lexicalBoost(String queryText, String content) {
        if (queryText == null || queryText.isBlank() || content == null || content.isBlank()) {
            return 1.0;
        }
        Set<String> queryTerms = lexicalTerms(queryText);
        if (queryTerms.isEmpty()) return 1.0;
        Set<String> contentTerms = lexicalTerms(content);
        if (contentTerms.isEmpty()) return 1.0;
        long matches = queryTerms.stream().filter(contentTerms::contains).count();
        double coverage = matches / (double) queryTerms.size();
        return 1.0 + Math.min(1.0, coverage);
    }

    private Set<String> lexicalTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() < 3) continue;
            if (Set.of("what", "the", "this", "that", "with", "from", "your", "does", "user").contains(token)) continue;
            terms.add(token);
        }
        return terms;
    }

    // ─── Store helpers ─────────────────────────────────────────────────

    private Map<String, Object> storeMemory(String userId, Map<String, Object> input, String kind) {
        String memType = stringVal(input, "memoryType", kind).toUpperCase();
        try {
            com.cloudqueryx.memory.MemoryType.valueOf(memType);
        } catch (IllegalArgumentException e) {
            memType = "FACT";
        }

        String content = requiredStr(input, "content");
        float[] embedding = embeddingVal(input.get("embedding"));
        if (embedding == null && embeddingService != null) {
            embedding = embeddingService.embed(content);
        }

        String namespace = stringVal(input, "namespace", DEFAULT_NAMESPACE);
        String scope = stringVal(input, "scope", "user");
        double conflictThreshold = doubleVal(input, "conflictThreshold", 0.85);
        boolean skipConflictCheck = boolVal(input, "skipConflictCheck", false);

        List<String> superseded = new ArrayList<>();
        if (!skipConflictCheck && embedding != null) {
            List<MemoryRepository.MemorySearchRow> conflicts = memoryRepo.findConflicting(
                    databaseId, namespace, embedding, conflictThreshold, memType, userId);
            for (MemoryRepository.MemorySearchRow conflict : conflicts) {
                memoryRepo.expire(databaseId, conflict.row().id());
                superseded.add(conflict.row().id());
                log.info("Conflict resolved: expired memory {} (similarity={}), replacing with new content",
                        conflict.row().id(), String.format("%.3f", conflict.similarity()));
            }
        }

        Map<String, Object> metadata = mapVal(input.get("metadata"));
        if (!superseded.isEmpty()) {
            metadata = new HashMap<>(metadata);
            metadata.put("supersedes", superseded);
        }

        Instant validUntil = null;
        if ("session".equals(scope)) {
            int sessionHours = intVal(input, "sessionTimeoutHours", 24);
            validUntil = Instant.now().plusSeconds(sessionHours * 3600L);
        }

        String id = stringVal(input, "id", UUID.randomUUID().toString());
        MemoryRepository.MemoryRow row = new MemoryRepository.MemoryRow(
                id, userId, namespace, memType, content, metadata, embedding,
                doubleVal(input, "importance", 0.5),
                doubleVal(input, "confidence", 1.0),
                doubleVal(input, "recency", 1.0),
                stringVal(input, "source", "context-api"),
                0, null, validUntil, null, null, scope
        );
        memoryRepo.upsert(databaseId, row);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("kind", "memory");
        result.put("type", memType);
        if (!superseded.isEmpty()) {
            result.put("conflictsResolved", superseded.size());
            result.put("superseded", superseded);
        }
        return result;
    }

    private Map<String, Object> storeVector(Map<String, Object> input) {
        float[] embedding = embeddingVal(input.get("embedding"));
        if (embedding == null && embeddingService != null) {
            String content = stringVal(input, "content", null);
            if (content != null && !content.isBlank()) {
                embedding = embeddingService.embed(content);
            }
        }
        if (embedding == null) throw new IllegalArgumentException("embedding or content required");
        String namespace = stringVal(input, "namespace", DEFAULT_NAMESPACE);
        String id = stringVal(input, "id", UUID.randomUUID().toString());
        vectorRepo.upsert(databaseId, namespace, id, embedding,
                stringVal(input, "content", null), mapVal(input.get("metadata")));
        return Map.of("id", id, "kind", "vector", "namespace", namespace);
    }

    private Map<String, Object> storeEntity(String userId, Map<String, Object> input) {
        String name = stringVal(input, "name", stringVal(input, "content", null));
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name or content required");
        }
        String description = stringVal(input, "description", stringVal(input, "content", null));
        float[] embedding = embeddingVal(input.get("embedding"));
        if (embedding == null && embeddingService != null) {
            String textToEmbed = (description != null && !description.isBlank()) ? name + " " + description : name;
            embedding = embeddingService.embed(textToEmbed);
        }

        String id = stringVal(input, "id", UUID.randomUUID().toString());
        String entityType = stringVal(input, "entityType", stringVal(input, "type", "CONTEXT_ENTITY"));
        GraphRepository.EntityRow entity = new GraphRepository.EntityRow(
                id, userId, entityType, name,
                description,
                mapVal(input.get("attributes")),
                embedding,
                doubleVal(input, "confidence", 1.0),
                stringVal(input, "source", "context-api"),
                null, null
        );
        graphRepo.upsertEntity(databaseId, entity);
        return Map.of("id", id, "kind", "entity", "entityType", entityType);
    }

    private Map<String, Object> storeRelationship(Map<String, Object> input) {
        String id = stringVal(input, "id", UUID.randomUUID().toString());
        GraphRepository.RelationshipRow rel = new GraphRepository.RelationshipRow(
                id,
                requiredStr(input, "sourceEntityId"),
                requiredStr(input, "targetEntityId"),
                stringVal(input, "relationshipType", "RELATED_TO"),
                mapVal(input.get("attributes")),
                doubleVal(input, "weight", 1.0),
                doubleVal(input, "confidence", 1.0),
                stringVal(input, "source", "context-api"),
                null, null, null
        );
        graphRepo.upsertRelationship(databaseId, rel);
        return Map.of("id", id, "kind", "relationship", "relationshipType", rel.relationshipType());
    }

    private Map<String, Object> storeEvent(String userId, Map<String, Object> input) {
        String id = stringVal(input, "id", UUID.randomUUID().toString());
        EventRepository.EventRow event = new EventRepository.EventRow(
                id, userId,
                stringVal(input, "eventType", stringVal(input, "type", "CONTEXT_EVENT")),
                stringVal(input, "action", stringVal(input, "content", null)),
                mapVal(input.get("properties")),
                stringVal(input, "sessionId", null),
                null
        );
        eventRepo.insert(databaseId, event);
        return Map.of("id", id, "kind", "event", "eventType", event.eventType());
    }

    // ─── Webhook helper ─────────────────────────────────────────────────

    private void fireWebhook(String eventType, Map<String, Object> payload) {
        if (webhookDispatcher != null) {
            webhookDispatcher.dispatch(databaseId, eventType, payload);
        }
    }

    // ─── Value extractors ──────────────────────────────────────────────

    private record ScoredItem(ContextRecallResult result, double score) {}

    private String requiredStr(Map<String, Object> map, String key) {
        String v = stringVal(map, key, null);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(key + " required");
        return v;
    }

    private String stringVal(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : def;
    }

    private int intVal(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) return Integer.parseInt(s);
        return def;
    }

    private double doubleVal(Map<String, Object> map, String key, double def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) return Double.parseDouble(s);
        return def;
    }

    private double doubleFromMap(Map<String, Object> map, String key, double def) {
        if (map == null || map.isEmpty()) return def;
        return doubleVal(map, key, def);
    }

    private boolean boolVal(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s);
        return def;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapVal(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> copy = new HashMap<>();
            raw.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        return Map.of();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::toLowerCase).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.toLowerCase());
        }
        return List.of();
    }

    private Instant instantVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        try {
            return Instant.parse(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private float[] embeddingVal(Object value) {
        if (!(value instanceof List<?> list)) return null;
        float[] embedding = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            embedding[i] = ((Number) list.get(i)).floatValue();
        }
        return embedding;
    }
}
