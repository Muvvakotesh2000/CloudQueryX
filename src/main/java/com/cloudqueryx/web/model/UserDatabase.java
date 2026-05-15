package com.cloudqueryx.web.model;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.learning.BehaviorEventStore;
import com.cloudqueryx.memory.MemoryStore;
import com.cloudqueryx.multimodal.MultimodalStore;
import com.cloudqueryx.semantic.SemanticGraph;
import com.cloudqueryx.vector.VectorStore;

import java.time.Instant;

public class UserDatabase {

    private final String id;
    private final String userId;
    private final String name;
    private final Instant createdAt;
    private final Catalog catalog;
    private final VectorStore vectorStore;
    private final MemoryStore memoryStore;
    private final SemanticGraph semanticGraph;
    private final MultimodalStore multimodalStore;
    private final BehaviorEventStore eventStore;

    public UserDatabase(String id, String userId, String name, int defaultVectorDimension) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.createdAt = Instant.now();
        this.catalog = new Catalog();
        this.vectorStore = new VectorStore(defaultVectorDimension);
        this.memoryStore = new MemoryStore(vectorStore);
        this.semanticGraph = new SemanticGraph(vectorStore);
        this.multimodalStore = new MultimodalStore(vectorStore);
        this.eventStore = new BehaviorEventStore();
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public Catalog getCatalog() { return catalog; }
    public VectorStore getVectorStore() { return vectorStore; }
    public MemoryStore getMemoryStore() { return memoryStore; }
    public SemanticGraph getSemanticGraph() { return semanticGraph; }
    public MultimodalStore getMultimodalStore() { return multimodalStore; }
    public BehaviorEventStore getEventStore() { return eventStore; }
}
