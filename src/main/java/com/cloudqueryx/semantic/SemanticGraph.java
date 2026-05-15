package com.cloudqueryx.semantic;

import com.cloudqueryx.vector.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SemanticGraph {

    private final Map<String, SemanticEntity> entities = new ConcurrentHashMap<>();
    private final Map<String, SemanticRelationship> relationships = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> outgoingEdges = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> incomingEdges = new ConcurrentHashMap<>();
    private final VectorStore vectorStore;

    public SemanticGraph(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void addEntity(SemanticEntity entity) {
        entities.put(entity.id(), entity);
        if (entity.embedding() != null) {
            vectorStore.insert("semantic_entities", new VectorRecord(
                    entity.id(), "semantic_entities", entity.embedding(),
                    Map.of("entityType", entity.entityType(), "name", entity.name()),
                    entity.description(), entity.createdAt(), null
            ));
        }
    }

    public void addRelationship(SemanticRelationship rel) {
        relationships.put(rel.id(), rel);
        outgoingEdges.computeIfAbsent(rel.sourceEntityId(), k -> ConcurrentHashMap.newKeySet())
                .add(rel.id());
        incomingEdges.computeIfAbsent(rel.targetEntityId(), k -> ConcurrentHashMap.newKeySet())
                .add(rel.id());
    }

    public SemanticEntity getEntity(String id) {
        return entities.get(id);
    }

    public List<SemanticEntity> findEntitiesByType(String entityType) {
        return entities.values().stream()
                .filter(e -> entityType.equalsIgnoreCase(e.entityType()))
                .collect(Collectors.toList());
    }

    public List<SemanticEntity> findEntitiesByUser(String userId) {
        return entities.values().stream()
                .filter(e -> userId.equals(e.userId()))
                .collect(Collectors.toList());
    }

    public List<SemanticRelationship> getRelationshipsFrom(String entityId) {
        Set<String> relIds = outgoingEdges.getOrDefault(entityId, Set.of());
        return relIds.stream()
                .map(relationships::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<SemanticRelationship> getRelationshipsTo(String entityId) {
        Set<String> relIds = incomingEdges.getOrDefault(entityId, Set.of());
        return relIds.stream()
                .map(relationships::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<SemanticEntity> findRelated(String entityId, String relationshipType, int maxDepth) {
        Set<String> visited = new HashSet<>();
        List<SemanticEntity> result = new ArrayList<>();
        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> depths = new HashMap<>();

        queue.add(entityId);
        depths.put(entityId, 0);
        visited.add(entityId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int depth = depths.get(current);
            if (depth > maxDepth) continue;

            for (SemanticRelationship rel : getRelationshipsFrom(current)) {
                if (relationshipType != null && !relationshipType.equalsIgnoreCase(rel.relationshipType()))
                    continue;
                String target = rel.targetEntityId();
                if (!visited.contains(target)) {
                    visited.add(target);
                    SemanticEntity targetEntity = entities.get(target);
                    if (targetEntity != null) {
                        result.add(targetEntity);
                        queue.add(target);
                        depths.put(target, depth + 1);
                    }
                }
            }
        }
        return result;
    }

    public List<SemanticEntity> semanticSearch(float[] queryEmbedding, int topK) {
        List<VectorSearchResult> results = vectorStore.search(
                "semantic_entities", queryEmbedding, topK, SimilarityMetric.COSINE);
        return results.stream()
                .map(vsr -> entities.get(vsr.record().id()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public boolean removeEntity(String id) {
        SemanticEntity removed = entities.remove(id);
        if (removed == null) return false;
        vectorStore.delete("semantic_entities", id);
        Set<String> outRels = outgoingEdges.remove(id);
        if (outRels != null) outRels.forEach(relationships::remove);
        Set<String> inRels = incomingEdges.remove(id);
        if (inRels != null) inRels.forEach(relationships::remove);
        return true;
    }

    public int entityCount() {
        return entities.size();
    }

    public int relationshipCount() {
        return relationships.size();
    }
}
