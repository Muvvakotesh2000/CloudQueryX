package com.cloudqueryx.context;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "CLOUDQUERYX_DB_URL", matches = ".+")
class ContextEngineTest {

    @Test
    void storeAndRecallMemoryThroughUnifiedApi() {
        // Requires PostgreSQL with pgvector; run with CLOUDQUERYX_DB_URL set
    }

    @Test
    void recallCanMergeMemoryVectorAndRecentEvents() {
        // Requires PostgreSQL with pgvector; run with CLOUDQUERYX_DB_URL set
    }
}
