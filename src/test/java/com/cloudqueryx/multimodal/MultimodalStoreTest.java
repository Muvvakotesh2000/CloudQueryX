package com.cloudqueryx.multimodal;

import com.cloudqueryx.vector.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MultimodalStoreTest {

    private MultimodalStore store;

    @BeforeEach
    void setUp() {
        VectorStore vectorStore = new VectorStore(3);
        store = new MultimodalStore(vectorStore);
    }

    @Test
    void storeAndRetrieveAsset() {
        MultimodalAsset asset = new MultimodalAsset(
                "a1", "user1", ModalityType.TEXT, "Doc1", "s3://bucket/doc.pdf",
                "application/pdf", "Extracted text content", "A summary",
                Map.of("source", "upload"), new float[]{0.1f, 0.2f, 0.3f},
                "text-embedding-ada-002", 1024, null, null);
        store.store(asset);

        List<MultimodalAsset> userAssets = store.findByUser("user1");
        assertThat(userAssets).hasSize(1);
        assertThat(userAssets.get(0).title()).isEqualTo("Doc1");
    }

    @Test
    void searchByEmbedding() {
        store.store(new MultimodalAsset("a1", "user1", ModalityType.TEXT, "Doc1", null,
                null, null, null, Map.of(), new float[]{1.0f, 0.0f, 0.0f}, null, 0, null, null));
        store.store(new MultimodalAsset("a2", "user1", ModalityType.IMAGE, "Img1", null,
                null, null, null, Map.of(), new float[]{0.0f, 1.0f, 0.0f}, null, 0, null, null));

        List<MultimodalAsset> results = store.search(new float[]{0.9f, 0.1f, 0.0f}, 5);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).id()).isEqualTo("a1");
    }

    @Test
    void findByModality() {
        store.store(new MultimodalAsset("a1", "user1", ModalityType.TEXT, "Doc1", null,
                null, null, null, Map.of(), new float[]{1.0f, 0.0f, 0.0f}, null, 0, null, null));
        store.store(new MultimodalAsset("a2", "user1", ModalityType.IMAGE, "Img1", null,
                null, null, null, Map.of(), new float[]{0.0f, 1.0f, 0.0f}, null, 0, null, null));
        store.store(new MultimodalAsset("a3", "user1", ModalityType.TEXT, "Doc2", null,
                null, null, null, Map.of(), new float[]{0.5f, 0.5f, 0.0f}, null, 0, null, null));

        List<MultimodalAsset> textAssets = store.findByModality(ModalityType.TEXT);
        assertThat(textAssets).hasSize(2);
    }

    @Test
    void modalityTypeValues() {
        assertThat(ModalityType.values()).contains(
                ModalityType.TEXT, ModalityType.IMAGE, ModalityType.AUDIO,
                ModalityType.VIDEO, ModalityType.CODE);
    }
}
