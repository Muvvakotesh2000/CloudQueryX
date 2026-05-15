package com.cloudqueryx.semantic;

import com.cloudqueryx.vector.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SemanticGraphTest {

    private SemanticGraph graph;

    @BeforeEach
    void setUp() {
        VectorStore vectorStore = new VectorStore(3);
        graph = new SemanticGraph(vectorStore);
    }

    @Test
    void addAndRetrieveEntity() {
        SemanticEntity entity = new SemanticEntity("e1", "user1", "person", "Alice",
                "Software engineer", Map.of("role", "SDE"), null, 1.0, null, null, null);
        graph.addEntity(entity);

        SemanticEntity found = graph.getEntity("e1");
        assertThat(found).isNotNull();
        assertThat(found.name()).isEqualTo("Alice");
        assertThat(found.entityType()).isEqualTo("person");
    }

    @Test
    void addRelationshipAndTraverse() {
        graph.addEntity(new SemanticEntity("e1", "user1", "person", "Alice", null, Map.of(), null, 1.0, null, null, null));
        graph.addEntity(new SemanticEntity("e2", "user1", "skill", "Java", null, Map.of(), null, 1.0, null, null, null));
        graph.addEntity(new SemanticEntity("e3", "user1", "skill", "Python", null, Map.of(), null, 1.0, null, null, null));

        graph.addRelationship(new SemanticRelationship("r1", "e1", "e2", "HAS_SKILL", Map.of(), 1.0, 1.0, null, null));
        graph.addRelationship(new SemanticRelationship("r2", "e1", "e3", "HAS_SKILL", Map.of(), 0.8, 1.0, null, null));

        List<SemanticRelationship> rels = graph.getRelationshipsFrom("e1");
        assertThat(rels).hasSize(2);

        List<SemanticEntity> related = graph.findRelated("e1", "HAS_SKILL", 1);
        assertThat(related).hasSize(2);
        assertThat(related).extracting(SemanticEntity::name).containsExactlyInAnyOrder("Java", "Python");
    }

    @Test
    void findEntitiesByType() {
        graph.addEntity(new SemanticEntity("e1", "user1", "person", "Alice", null, Map.of(), null, 1.0, null, null, null));
        graph.addEntity(new SemanticEntity("e2", "user1", "person", "Bob", null, Map.of(), null, 1.0, null, null, null));
        graph.addEntity(new SemanticEntity("e3", "user1", "skill", "Java", null, Map.of(), null, 1.0, null, null, null));

        List<SemanticEntity> persons = graph.findEntitiesByType("person");
        assertThat(persons).hasSize(2);
    }

    @Test
    void multiHopTraversal() {
        graph.addEntity(new SemanticEntity("e1", "u1", "person", "Alice", null, Map.of(), null, 1.0, null, null, null));
        graph.addEntity(new SemanticEntity("e2", "u1", "company", "Acme", null, Map.of(), null, 1.0, null, null, null));
        graph.addEntity(new SemanticEntity("e3", "u1", "location", "NYC", null, Map.of(), null, 1.0, null, null, null));

        graph.addRelationship(new SemanticRelationship("r1", "e1", "e2", "WORKS_AT", Map.of(), 1.0, 1.0, null, null));
        graph.addRelationship(new SemanticRelationship("r2", "e2", "e3", "LOCATED_IN", Map.of(), 1.0, 1.0, null, null));

        List<SemanticEntity> related = graph.findRelated("e1", null, 2);
        assertThat(related).hasSize(2);
        assertThat(related).extracting(SemanticEntity::name).containsExactlyInAnyOrder("Acme", "NYC");
    }

    @Test
    void removeEntity() {
        graph.addEntity(new SemanticEntity("e1", "u1", "person", "Alice", null, Map.of(), null, 1.0, null, null, null));
        assertThat(graph.entityCount()).isEqualTo(1);

        graph.removeEntity("e1");
        assertThat(graph.entityCount()).isEqualTo(0);
        assertThat(graph.getEntity("e1")).isNull();
    }
}
