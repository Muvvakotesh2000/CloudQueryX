package com.cloudqueryx.web;

import com.cloudqueryx.web.api.ApiServer;
import com.cloudqueryx.web.api.JsonUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "CLOUDQUERYX_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiServerIntegrationTest {

    private static ApiServer server;
    private static int port;
    private static String authToken;
    private static String databaseId;

    @BeforeAll
    static void startServer() throws Exception {
        port = 18080 + new Random().nextInt(1000);
        server = new ApiServer(port);
        server.start();
        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    @Order(1)
    void signup() throws Exception {
        Map<String, Object> response = post("/api/auth/signup",
                Map.of("email", "test@cloudqueryx.com", "password", "securepass123"), null);
        assertThat(response).containsKey("token");
        assertThat(response).containsKey("user");
        authToken = (String) response.get("token");
        assertThat(authToken).isNotEmpty();
    }

    @Test
    @Order(2)
    void loginWithCorrectCredentials() throws Exception {
        Map<String, Object> response = post("/api/auth/login",
                Map.of("email", "test@cloudqueryx.com", "password", "securepass123"), null);
        assertThat(response).containsKey("token");
        authToken = (String) response.get("token");
    }

    @Test
    @Order(3)
    void loginWithWrongPasswordReturns401() throws Exception {
        HttpURLConnection conn = request("POST", "/api/auth/login",
                Map.of("email", "test@cloudqueryx.com", "password", "wrongpass"), null);
        assertThat(conn.getResponseCode()).isEqualTo(401);
    }

    @Test
    @Order(4)
    void unauthenticatedRequestReturns401() throws Exception {
        HttpURLConnection conn = request("GET", "/api/databases", null, null);
        assertThat(conn.getResponseCode()).isEqualTo(401);
    }

    @Test
    @Order(5)
    void createDatabase() throws Exception {
        Map<String, Object> response = post("/api/databases",
                Map.of("name", "TestDB"), authToken);
        assertThat(response).containsKey("id");
        assertThat(response.get("name")).isEqualTo("TestDB");
        databaseId = (String) response.get("id");
    }

    @Test
    @Order(6)
    void listDatabases() throws Exception {
        Map<String, Object> response = get("/api/databases", authToken, null);
        assertThat(response).containsKey("databases");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dbs = (List<Map<String, Object>>) response.get("databases");
        assertThat(dbs).hasSizeGreaterThanOrEqualTo(1);
        assertThat(dbs.get(0).get("name")).isEqualTo("TestDB");
    }

    @Test
    @Order(7)
    void uploadCsvAndListTables() throws Exception {
        String csv = "id,name,age\n1,Alice,30\n2,Bob,25\n3,Charlie,35";
        Map<String, Object> response = postWithDbId("/api/tables",
                Map.of("action", "upload_csv", "tableName", "people", "content", csv),
                authToken, databaseId);
        assertThat(response.get("table")).isEqualTo("people");

        Map<String, Object> tables = getWithDbId("/api/tables", authToken, databaseId);
        assertThat(tables).containsKey("tables");
    }

    @Test
    @Order(8)
    void executeSqlQuery() throws Exception {
        Map<String, Object> response = postWithDbId("/api/query",
                Map.of("sql", "SELECT * FROM people WHERE age > 25"),
                authToken, databaseId);
        assertThat(response).containsKey("columns");
        assertThat(response).containsKey("rows");
        int rowCount = ((Number) response.get("rowCount")).intValue();
        assertThat(rowCount).isEqualTo(2);
    }

    @Test
    @Order(9)
    void executeExplainQuery() throws Exception {
        Map<String, Object> response = postWithDbId("/api/query",
                Map.of("sql", "SELECT name FROM people", "explain", true),
                authToken, databaseId);
        assertThat(response).containsKey("logicalPlan");
        assertThat(response).containsKey("optimizedPlan");
        assertThat(response).containsKey("physicalPlan");
    }

    @Test
    @Order(10)
    void invalidSqlReturns400() throws Exception {
        HttpURLConnection conn = requestWithDbId("POST", "/api/query",
                Map.of("sql", "SELEC INVALID SYNTAX"), authToken, databaseId);
        assertThat(conn.getResponseCode()).isEqualTo(400);
    }

    @Test
    @Order(11)
    void storeAndRecallMemory() throws Exception {
        float[] embedding = new float[384];
        Random rng = new Random(42);
        List<Float> embList = new ArrayList<>();
        for (int i = 0; i < 384; i++) {
            embedding[i] = rng.nextFloat() * 2 - 1;
            embList.add(embedding[i]);
        }

        Map<String, Object> storeResp = postWithDbId("/api/memory",
                Map.of("action", "store", "content", "Test memory content",
                        "type", "FACT", "importance", 0.8, "embedding", embList),
                authToken, databaseId);
        assertThat(storeResp).containsKey("id");

        Map<String, Object> recallResp = postWithDbId("/api/memory",
                Map.of("action", "recall", "embedding", embList, "topK", 5),
                authToken, databaseId);
        assertThat(recallResp).containsKey("results");
    }

    @Test
    @Order(12)
    void vectorInsertAndSearch() throws Exception {
        Random rng = new Random(42);
        List<Float> embList = new ArrayList<>();
        for (int i = 0; i < 384; i++) embList.add(rng.nextFloat() * 2 - 1);

        Map<String, Object> insertResp = postWithDbId("/api/vectors",
                Map.of("action", "insert", "id", "v1", "namespace", "test",
                        "embedding", embList, "content", "test vector"),
                authToken, databaseId);
        assertThat(insertResp.get("id")).isEqualTo("v1");

        Map<String, Object> searchResp = postWithDbId("/api/vectors",
                Map.of("action", "search", "namespace", "test",
                        "embedding", embList, "topK", 5),
                authToken, databaseId);
        assertThat(searchResp).containsKey("results");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) searchResp.get("results");
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).get("id")).isEqualTo("v1");
    }

    @Test
    @Order(13)
    void semanticGraphOperations() throws Exception {
        Map<String, Object> addEntity = postWithDbId("/api/semantic",
                Map.of("action", "add_entity", "id", "e1", "entityType", "person",
                        "name", "Alice", "description", "Engineer"),
                authToken, databaseId);
        assertThat(addEntity.get("id")).isEqualTo("e1");

        Map<String, Object> addEntity2 = postWithDbId("/api/semantic",
                Map.of("action", "add_entity", "id", "e2", "entityType", "skill",
                        "name", "Java"),
                authToken, databaseId);
        assertThat(addEntity2.get("id")).isEqualTo("e2");

        postWithDbId("/api/semantic",
                Map.of("action", "add_relationship", "sourceEntityId", "e1",
                        "targetEntityId", "e2", "relationshipType", "HAS_SKILL"),
                authToken, databaseId);

        Map<String, Object> related = postWithDbId("/api/semantic",
                Map.of("action", "find_related", "entityId", "e1",
                        "relationshipType", "HAS_SKILL", "maxDepth", 1),
                authToken, databaseId);
        assertThat(related).containsKey("results");
    }

    @Test
    @Order(14)
    void behaviorEvents() throws Exception {
        Map<String, Object> ingestResp = postWithDbId("/api/events",
                Map.of("eventType", "QUERY", "action", "SELECT * FROM people",
                        "properties", Map.of("source", "test")),
                authToken, databaseId);
        assertThat(ingestResp).containsKey("id");

        Map<String, Object> eventsResp = getWithDbId("/api/events?limit=10", authToken, databaseId);
        assertThat(eventsResp).containsKey("events");
    }

    @Test
    @Order(15)
    void databaseIsolation() throws Exception {
        String signup2Resp = rawPost("/api/auth/signup",
                Map.of("email", "other@cloudqueryx.com", "password", "otherpass123"), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> user2 = new com.fasterxml.jackson.databind.ObjectMapper().readValue(signup2Resp, Map.class);
        String otherToken = (String) user2.get("token");

        HttpURLConnection conn = requestWithDbId("GET", "/api/tables", null, otherToken, databaseId);
        assertThat(conn.getResponseCode()).isEqualTo(404);
    }

    @Test
    @Order(16)
    void deleteDatabase() throws Exception {
        HttpURLConnection conn = request("DELETE", "/api/databases/" + databaseId, null, authToken);
        assertThat(conn.getResponseCode()).isEqualTo(200);

        Map<String, Object> list = get("/api/databases", authToken, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dbs = (List<Map<String, Object>>) list.get("databases");
        assertThat(dbs).isEmpty();
    }

    @Test
    @Order(17)
    void staticFilesServed() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://localhost:" + port + "/").toURL().openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getContentType()).contains("text/html");
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body, String token) throws Exception {
        HttpURLConnection conn = request("POST", path, body, token);
        assertThat(conn.getResponseCode()).isBetween(200, 201);
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(conn.getInputStream(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postWithDbId(String path, Map<String, Object> body, String token, String dbId) throws Exception {
        HttpURLConnection conn = requestWithDbId("POST", path, body, token, dbId);
        assertThat(conn.getResponseCode()).isBetween(200, 201);
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(conn.getInputStream(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, String token, String dbId) throws Exception {
        HttpURLConnection conn = request("GET", path, null, token);
        assertThat(conn.getResponseCode()).isEqualTo(200);
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(conn.getInputStream(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getWithDbId(String path, String token, String dbId) throws Exception {
        HttpURLConnection conn = requestWithDbId("GET", path, null, token, dbId);
        assertThat(conn.getResponseCode()).isEqualTo(200);
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(conn.getInputStream(), Map.class);
    }

    private String rawPost(String path, Map<String, Object> body, String token) throws Exception {
        HttpURLConnection conn = request("POST", path, body, token);
        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private HttpURLConnection request(String method, String path, Map<String, Object> body, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://localhost:" + port + path).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            conn.setDoOutput(true);
            conn.getOutputStream().write(JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private HttpURLConnection requestWithDbId(String method, String path, Map<String, Object> body, String token, String dbId) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://localhost:" + port + path).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
        if (dbId != null) conn.setRequestProperty("X-Database-Id", dbId);
        if (body != null) {
            conn.setDoOutput(true);
            conn.getOutputStream().write(JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }
}
