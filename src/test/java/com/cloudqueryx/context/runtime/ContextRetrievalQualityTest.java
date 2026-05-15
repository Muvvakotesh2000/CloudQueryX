package com.cloudqueryx.context.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContextRetrievalQualityTest {

    @Test
    void capsRepetitiveTemplateChunksBelowSpecificArchitectureMemories() throws Exception {
        ContextRetrievalService service = new ContextRetrievalService(
                null, null, null, new TokenEstimator()
        );

        String repetitiveChunk = """
                Summary: CloudQueryX is a provider-neutral Context Runtime and Query Planner.
                Architecture: Website UI calls Java API Server and persists in Supabase PostgreSQL with pgvector.
                Retrieval: queries search memories, source chunks, graph relationships, events, and vector embeddings.
                Detail 1 for architecture: context explains memory recall, source chunking, graph traversal, event freshness, pgvector similarity, and explainability.
                Detail 2 for architecture: context explains memory recall, source chunking, graph traversal, event freshness, pgvector similarity, and explainability.
                Detail 3 for architecture: context explains memory recall, source chunking, graph traversal, event freshness, pgvector similarity, and explainability.
                """;
        String specificMemory = "CloudQueryX architecture is Website UI -> Java API Server -> Context Runtime -> Supabase PostgreSQL with pgvector.";

        double chunkScore = score(service, 0.76, 0.79, 1.0, 0.75, "SOURCE_CHUNK", repetitiveChunk);
        double memoryScore = score(service, 0.80, 0.67, 1.0, 0.95, "MEMORY", specificMemory);

        assertTrue(chunkScore < 0.83);
        assertTrue(memoryScore > chunkScore);
    }

    @Test
    void capsGenericSyntheticGraphWorkers() throws Exception {
        ContextRetrievalService service = new ContextRetrievalService(
                null, null, null, new TokenEstimator()
        );

        String genericWorker = "Context Worker 45 (ADAPTER): Production-scale ADAPTER component used to test CloudQueryX graph retrieval, relationship scoring, event relevance, pgvector search, and ownership isolation.";

        double score = score(service, 0.69, 0.38, 1.0, 0.9, "ENTITY", genericWorker);

        assertTrue(score <= 0.50);
    }

    @Test
    void capsNoisyProductionMemoryVariantsBelowCanonicalFacts() throws Exception {
        ContextRetrievalService service = new ContextRetrievalService(
                null, null, null, new TokenEstimator()
        );

        String noisyMemory = "CloudQueryX architecture is Website UI -> Java API Server -> Context Runtime -> Supabase PostgreSQL with pgvector. Production memory 114 adds events context for workspace isolation, scoring, explainability, and rigorous end-to-end testing.";
        String canonicalMemory = "CloudQueryX architecture: Website UI -> Java API Server -> Context Runtime -> Memory Engine, Source Store, Knowledge Graph, Event Store -> Supabase PostgreSQL with pgvector.";

        double noisyScore = score(service, 0.80, 0.67, 1.0, 0.95, "MEMORY", noisyMemory);
        double canonicalScore = score(service, 0.80, 0.67, 1.0, 1.0, "MEMORY", canonicalMemory);

        assertTrue(noisyScore <= 0.62);
        assertTrue(canonicalScore > noisyScore);
    }

    @Test
    void duplicateSuppressionDoesNotCollapseDifferentEvidenceTypes() throws Exception {
        ContextRetrievalService service = new ContextRetrievalService(
                null, null, null, new TokenEstimator()
        );
        Set<String> signatures = new HashSet<>();
        String content = "CloudQueryX architecture uses memory, sources, graph, events, Supabase PostgreSQL, and pgvector.";

        boolean sourceDuplicate = isNearDuplicate(service, signatures, result("SOURCE_CHUNK", "source", content));
        boolean memoryDuplicate = isNearDuplicate(service, signatures, result("MEMORY", "memory", content));

        assertFalse(sourceDuplicate);
        assertFalse(memoryDuplicate);
    }

    private double score(ContextRetrievalService service, double vectorScore, double textScore,
                         double freshness, double importance, String type, String content) throws Exception {
        Method method = ContextRetrievalService.class.getDeclaredMethod(
                "score", double.class, double.class, double.class, double.class, String.class, String.class
        );
        method.setAccessible(true);
        return (double) method.invoke(service, vectorScore, textScore, freshness, importance, type, content);
    }

    private boolean isNearDuplicate(ContextRetrievalService service, Set<String> signatures,
                                    RetrievalResult result) throws Exception {
        Method method = ContextRetrievalService.class.getDeclaredMethod(
                "isNearDuplicate", Set.class, RetrievalResult.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(service, signatures, result);
    }

    private RetrievalResult result(String type, String id, String content) {
        return new RetrievalResult(
                type, id, "source-" + id, "chunk-" + id, "memory-" + id,
                "test", "FACT", content, 20, 0.8, 0.8, 1.0, 0.8,
                "test", Map.of(), Instant.now()
        );
    }
}
