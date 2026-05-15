package com.cloudqueryx.memory;

import com.cloudqueryx.vector.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MemoryStoreTest {

    private MemoryStore memoryStore;
    private VectorStore vectorStore;

    @BeforeEach
    void setUp() {
        vectorStore = new VectorStore(3);
        memoryStore = new MemoryStore(vectorStore);
    }

    @Test
    void storeAndRecall() {
        MemoryRecord record = new MemoryRecord("m1", "user1", "default",
                MemoryType.FACT, "The sky is blue", Map.of(),
                new float[]{1.0f, 0.0f, 0.0f}, 0.8, 1.0, 1.0, "test", null, null, null, 0);

        memoryStore.store(record);
        MemoryRecord recalled = memoryStore.recall("m1");

        assertThat(recalled).isNotNull();
        assertThat(recalled.content()).isEqualTo("The sky is blue");
        assertThat(recalled.accessCount()).isEqualTo(1);
    }

    @Test
    void similarityRecall() {
        memoryStore.store(new MemoryRecord("m1", "user1", "default",
                MemoryType.FACT, "Java is a programming language", Map.of(),
                new float[]{1.0f, 0.0f, 0.0f}, 0.9, 1.0, 1.0, null, null, null, null, 0));
        memoryStore.store(new MemoryRecord("m2", "user1", "default",
                MemoryType.FACT, "Python is a programming language", Map.of(),
                new float[]{0.8f, 0.2f, 0.0f}, 0.7, 1.0, 1.0, null, null, null, null, 0));
        memoryStore.store(new MemoryRecord("m3", "user1", "default",
                MemoryType.PREFERENCE, "User prefers dark mode", Map.of(),
                new float[]{0.0f, 1.0f, 0.0f}, 0.5, 1.0, 1.0, null, null, null, null, 0));

        List<MemoryRecallResult> results = memoryStore.recallSimilar(
                "default", new float[]{0.9f, 0.1f, 0.0f}, 2, null);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).record().id()).isIn("m1", "m2");
        assertThat(results.get(0).explanation()).contains("similarity");
    }

    @Test
    void recallByType() {
        memoryStore.store(new MemoryRecord("m1", "user1", "default",
                MemoryType.FACT, "fact1", Map.of(),
                new float[]{1.0f, 0.0f, 0.0f}, 0.5, 1.0, 1.0, null, null, null, null, 0));
        memoryStore.store(new MemoryRecord("m2", "user1", "default",
                MemoryType.PREFERENCE, "pref1", Map.of(),
                new float[]{0.5f, 0.5f, 0.0f}, 0.5, 1.0, 1.0, null, null, null, null, 0));

        List<MemoryRecallResult> results = memoryStore.recallSimilar(
                "default", new float[]{1.0f, 0.0f, 0.0f}, 10, MemoryType.FACT);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).record().type()).isEqualTo(MemoryType.FACT);
    }

    @Test
    void forgetMemory() {
        memoryStore.store(new MemoryRecord("m1", "user1", "default",
                MemoryType.FACT, "to forget", Map.of(),
                new float[]{1.0f, 0.0f, 0.0f}, 0.5, 1.0, 1.0, null, null, null, null, 0));

        assertThat(memoryStore.forget("m1")).isTrue();
        assertThat(memoryStore.recall("m1")).isNull();
        assertThat(memoryStore.size()).isEqualTo(0);
    }

    @Test
    void updateImportance() {
        memoryStore.store(new MemoryRecord("m1", "user1", "default",
                MemoryType.FACT, "important fact", Map.of(), null,
                0.5, 1.0, 1.0, null, null, null, null, 0));

        assertThat(memoryStore.updateImportance("m1", 0.95)).isTrue();
        MemoryRecord recalled = memoryStore.recall("m1");
        assertThat(recalled.importance()).isEqualTo(0.95);
    }

    @Test
    void getUserContext() {
        for (int i = 0; i < 10; i++) {
            memoryStore.store(new MemoryRecord("m" + i, "user1", "default",
                    MemoryType.FACT, "fact " + i, Map.of(), null,
                    i * 0.1, 1.0, 1.0, null, null, null, null, 0));
        }

        List<MemoryRecord> context = memoryStore.getUserContext("user1", 3);
        assertThat(context).hasSize(3);
        assertThat(context.get(0).importance()).isGreaterThanOrEqualTo(context.get(1).importance());
    }

    @Test
    void summarizeMemories() {
        memoryStore.store(new MemoryRecord("m1", "user1", "default",
                MemoryType.FACT, "Java expertise", Map.of(), null,
                0.9, 1.0, 1.0, null, null, null, null, 0));
        memoryStore.store(new MemoryRecord("m2", "user1", "default",
                MemoryType.PREFERENCE, "Likes dark mode", Map.of(), null,
                0.5, 1.0, 1.0, null, null, null, null, 0));

        String summary = memoryStore.summarizeMemories("user1", null);
        assertThat(summary).contains("user1");
        assertThat(summary).contains("Total memories: 2");
    }

    @Test
    void getByUser() {
        memoryStore.store(new MemoryRecord("m1", "user1", "default",
                MemoryType.FACT, "fact1", Map.of(), null, 0.5, 1.0, 1.0, null, null, null, null, 0));
        memoryStore.store(new MemoryRecord("m2", "user2", "default",
                MemoryType.FACT, "fact2", Map.of(), null, 0.5, 1.0, 1.0, null, null, null, null, 0));

        assertThat(memoryStore.getByUser("user1")).hasSize(1);
        assertThat(memoryStore.getByUser("user2")).hasSize(1);
    }
}
