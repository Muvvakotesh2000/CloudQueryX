package com.cloudqueryx.learning;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class BehaviorEventStoreTest {

    private BehaviorEventStore store;

    @BeforeEach
    void setUp() {
        store = new BehaviorEventStore();
    }

    @Test
    void ingestAndQueryByUser() {
        store.ingest(new BehaviorEvent("e1", "user1", "QUERY", "SELECT *", Map.of(), "s1", null));
        store.ingest(new BehaviorEvent("e2", "user2", "QUERY", "SELECT 1", Map.of(), "s2", null));
        store.ingest(new BehaviorEvent("e3", "user1", "CLICK", "button", Map.of(), "s1", null));

        List<BehaviorEvent> user1Events = store.queryByUser("user1", 10);
        assertThat(user1Events).hasSize(2);
    }

    @Test
    void queryByType() {
        store.ingest(new BehaviorEvent("e1", "user1", "QUERY", "SELECT *", Map.of(), "s1", null));
        store.ingest(new BehaviorEvent("e2", "user1", "CLICK", "button", Map.of(), "s1", null));
        store.ingest(new BehaviorEvent("e3", "user1", "QUERY", "SELECT 1", Map.of(), "s1", null));

        List<BehaviorEvent> queries = store.queryByType("QUERY", 10);
        assertThat(queries).hasSize(2);
    }

    @Test
    void queryWithLimit() {
        for (int i = 0; i < 20; i++) {
            store.ingest(new BehaviorEvent("e" + i, "user1", "QUERY", "q" + i, Map.of(), "s1", null));
        }

        List<BehaviorEvent> limited = store.queryByUser("user1", 5);
        assertThat(limited).hasSize(5);
    }

    @Test
    void eventTimestampAutoSet() {
        BehaviorEvent event = new BehaviorEvent("e1", "user1", "QUERY", "SELECT 1", Map.of(), "s1", null);
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void size() {
        assertThat(store.size()).isEqualTo(0);
        store.ingest(new BehaviorEvent("e1", "user1", "QUERY", "SELECT 1", Map.of(), "s1", null));
        assertThat(store.size()).isEqualTo(1);
    }
}
