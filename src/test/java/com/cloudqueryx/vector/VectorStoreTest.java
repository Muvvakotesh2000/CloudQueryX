package com.cloudqueryx.vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class VectorStoreTest {

    private VectorStore store;

    @BeforeEach
    void setUp() {
        store = new VectorStore(3);
    }

    @Test
    void insertAndRetrieve() {
        VectorRecord record = new VectorRecord("v1", "default",
                new float[]{1.0f, 0.0f, 0.0f}, Map.of("label", "x-axis"),
                "unit vector along x", null, null);
        store.insert("default", record);

        VectorIndex idx = store.getNamespace("default");
        assertThat(idx.size()).isEqualTo(1);
        assertThat(idx.get("v1").content()).isEqualTo("unit vector along x");
    }

    @Test
    void cosineSearch() {
        store.insert("default", new VectorRecord("v1", "default",
                new float[]{1.0f, 0.0f, 0.0f}, Map.of(), "x", null, null));
        store.insert("default", new VectorRecord("v2", "default",
                new float[]{0.0f, 1.0f, 0.0f}, Map.of(), "y", null, null));
        store.insert("default", new VectorRecord("v3", "default",
                new float[]{0.7f, 0.7f, 0.0f}, Map.of(), "xy", null, null));

        List<VectorSearchResult> results = store.search("default",
                new float[]{1.0f, 0.0f, 0.0f}, 2, SimilarityMetric.COSINE);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).record().id()).isEqualTo("v1");
        assertThat(results.get(0).score()).isCloseTo(1.0f, within(0.001f));
    }

    @Test
    void dotProductSearch() {
        store.insert("default", new VectorRecord("a", "default",
                new float[]{3.0f, 0.0f, 0.0f}, Map.of(), null, null, null));
        store.insert("default", new VectorRecord("b", "default",
                new float[]{1.0f, 1.0f, 1.0f}, Map.of(), null, null, null));

        List<VectorSearchResult> results = store.search("default",
                new float[]{1.0f, 0.0f, 0.0f}, 2, SimilarityMetric.DOT_PRODUCT);

        assertThat(results.get(0).record().id()).isEqualTo("a");
        assertThat(results.get(0).score()).isCloseTo(3.0f, within(0.001f));
    }

    @Test
    void filteredSearch() {
        store.insert("default", new VectorRecord("v1", "default",
                new float[]{1.0f, 0.0f, 0.0f}, Map.of("color", "red"), "red item", null, null));
        store.insert("default", new VectorRecord("v2", "default",
                new float[]{0.9f, 0.1f, 0.0f}, Map.of("color", "blue"), "blue item", null, null));

        List<VectorSearchResult> results = store.search("default",
                new float[]{1.0f, 0.0f, 0.0f}, 10, SimilarityMetric.COSINE,
                r -> "red".equals(r.metadata().get("color")));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).record().id()).isEqualTo("v1");
    }

    @Test
    void hybridSearch() {
        store.insert("default", new VectorRecord("v1", "default",
                new float[]{1.0f, 0.0f, 0.0f}, Map.of(), "java distributed systems", null, null));
        store.insert("default", new VectorRecord("v2", "default",
                new float[]{0.9f, 0.1f, 0.0f}, Map.of(), "python machine learning", null, null));

        List<VectorSearchResult> results = store.hybridSearch("default",
                new float[]{1.0f, 0.0f, 0.0f}, 10, SimilarityMetric.COSINE,
                "java", null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).record().content()).contains("java");
    }

    @Test
    void deleteRecord() {
        store.insert("default", new VectorRecord("v1", "default",
                new float[]{1.0f, 0.0f, 0.0f}, Map.of(), null, null, null));
        assertThat(store.totalRecords()).isEqualTo(1);

        boolean deleted = store.delete("default", "v1");
        assertThat(deleted).isTrue();
        assertThat(store.totalRecords()).isEqualTo(0);
    }

    @Test
    void updateRecord() {
        store.insert("default", new VectorRecord("v1", "default",
                new float[]{1.0f, 0.0f, 0.0f}, Map.of("label", "old"), null, null, null));

        store.getNamespace("default").update("v1",
                new float[]{0.0f, 1.0f, 0.0f}, Map.of("label", "new"));

        VectorRecord updated = store.getNamespace("default").get("v1");
        assertThat(updated.embedding()[1]).isEqualTo(1.0f);
        assertThat(updated.metadata().get("label")).isEqualTo("new");
    }

    @Test
    void namespaceIsolation() {
        store.insert("ns1", new VectorRecord("v1", "ns1",
                new float[]{1.0f, 0.0f, 0.0f}, Map.of(), null, null, null));
        store.insert("ns2", new VectorRecord("v2", "ns2",
                new float[]{0.0f, 1.0f, 0.0f}, Map.of(), null, null, null));

        assertThat(store.getNamespace("ns1").size()).isEqualTo(1);
        assertThat(store.getNamespace("ns2").size()).isEqualTo(1);
        assertThat(store.listNamespaces()).containsExactlyInAnyOrder("ns1", "ns2");
    }

    @Test
    void dimensionMismatchThrows() {
        assertThatThrownBy(() ->
                store.insert("default", new VectorRecord("v1", "default",
                        new float[]{1.0f, 0.0f}, Map.of(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
