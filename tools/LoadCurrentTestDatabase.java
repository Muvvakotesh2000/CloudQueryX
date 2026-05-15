import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class LoadCurrentTestDatabase {
    private static final String DATABASE_ID = "3ae74b24-aa20-4e03-b8ea-db7dcd6110ea";
    private static final String USER_ID = "afb20018-35ce-42a5-941e-1c10f01b9b36";
    private static long targetBytes = 1024L * 1024L * 1024L;
    private static final int SOURCE_COUNT = 64;
    private static final int CHUNK_CHARS = 48 * 1024;
    private static String dbUrl;
    private static String dbUser;
    private static String dbPassword;

    public static void main(String[] args) throws Exception {
        Map<String, String> env = readEnv(Path.of(".env"));
        dbUrl = required(env, "CLOUDQUERYX_DB_URL");
        dbUser = required(env, "CLOUDQUERYX_DB_USER");
        dbPassword = required(env, "CLOUDQUERYX_DB_PASSWORD");
        if (args.length > 0 && args[0].matches("\\d+")) {
            targetBytes = Long.parseLong(args[0]) * 1024L * 1024L;
        }

        Instant started = Instant.now();
        Connection connection = connect();
        try {
            connection.setAutoCommit(false);
            clearDatabase(connection);
            connection.commit();
            if (args.length > 0 && "clear-only".equalsIgnoreCase(args[0])) {
                System.out.println("Cleared current test database only.");
                return;
            }

            LoadStats stats = new LoadStats();
            insertGraphCorpus(connection, stats);
            connection.commit();

            connection = loadSources(connection, stats);
            insertMemories(connection, stats);
            insertEvents(connection, stats);
            connection.commit();

            System.out.println("Loaded current test database only.");
            System.out.println("databaseId=" + DATABASE_ID);
            System.out.println("sources=" + stats.sources);
            System.out.println("chunks=" + stats.chunks);
            System.out.println("memories=" + stats.memories);
            System.out.println("entities=" + stats.entities);
            System.out.println("relationships=" + stats.relationships);
            System.out.println("events=" + stats.events);
            System.out.printf(Locale.ROOT, "payloadMiB=%.1f%n", stats.bytes / 1024.0 / 1024.0);
            System.out.println("elapsed=" + Duration.between(started, Instant.now()).toSeconds() + "s");
        } finally {
            connection.close();
        }
    }

    private static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
        }
        connection.setAutoCommit(false);
        connection.setReadOnly(false);
        return connection;
    }

    private static void clearDatabase(Connection connection) throws SQLException {
        delete(connection, "context_bundle_items");
        delete(connection, "context_bundles");
        delete(connection, "context_embeddings");
        delete(connection, "context_chunks");
        delete(connection, "sources");
        delete(connection, "memory_embeddings");
        delete(connection, "memories");
        delete(connection, "relationships");
        delete(connection, "entities");
        delete(connection, "events");
        delete(connection, "vectors");
        delete(connection, "webhooks");
        if (tableExists(connection, "user_table_rows") && tableExists(connection, "user_tables")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM user_table_rows WHERE table_id IN (SELECT id FROM user_tables WHERE database_id = ?::uuid)")) {
                statement.setString(1, DATABASE_ID);
                int rows = statement.executeUpdate();
                System.out.println("deleted " + rows + " from user_table_rows");
            }
        }
        delete(connection, "user_tables");
    }

    private static void delete(Connection connection, String table) throws SQLException {
        if (!tableExists(connection, table)) {
            System.out.println("skipped missing table " + table);
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE database_id = ?::uuid")) {
            statement.setString(1, DATABASE_ID);
            int rows = statement.executeUpdate();
            System.out.println("deleted " + rows + " from " + table);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, "public." + table);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private static void insertGraphCorpus(Connection connection, LoadStats stats) throws SQLException {
        insertEntity(connection, "ent_cloudqueryx", "PROJECT", "CloudQueryX",
                "Provider-neutral Context Runtime and Query Planner for LLM applications.", stats);
        insertEntity(connection, "ent_context_runtime", "SERVICE", "Context Runtime",
                "Service layer that retrieves memories, source chunks, knowledge graph relationships, and events before building token-budgeted context bundles.", stats);
        insertEntity(connection, "ent_memory_engine", "SERVICE", "Memory Engine",
                "Old subsystem that keeps STORE, RECALL, RELATE, LEARN, FORGET, and CORRECT operations intact.", stats);
        insertEntity(connection, "ent_source_store", "SERVICE", "Source Store",
                "Stores documents, code, logs, notes, markdown, config text, and chunked source content.", stats);
        insertEntity(connection, "ent_pgvector", "EXTENSION", "pgvector",
                "PostgreSQL extension used for embedding similarity search across memories and context chunks.", stats);
        insertEntity(connection, "ent_supabase", "DATABASE", "Supabase PostgreSQL",
                "Persistent relational storage backend for users, databases, memories, sources, graph entities, relationships, events, and context bundles.", stats);
        insertEntity(connection, "ent_external_llm", "EXTERNAL_SYSTEM", "External LLM App",
                "Application that receives formatted context from CloudQueryX and sends it to any LLM provider.", stats);

        insertRelationship(connection, "rel_cloud_uses_supabase", "CloudQueryX", "Supabase PostgreSQL", "USES",
                "CloudQueryX uses Supabase PostgreSQL as durable storage for all user-scoped context data.", stats);
        insertRelationship(connection, "rel_cloud_uses_pgvector", "CloudQueryX", "pgvector", "USES",
                "CloudQueryX uses pgvector for vector retrieval over embeddings.", stats);
        insertRelationship(connection, "rel_runtime_combines_memory", "Context Runtime", "Memory Engine", "COMBINES",
                "The Context Runtime combines old memory recall with source chunks, graph relationships, and events.", stats);
        insertRelationship(connection, "rel_runtime_reads_sources", "Context Runtime", "Source Store", "READS",
                "The Context Runtime retrieves source chunks from stored documents, code, logs, notes, and configs.", stats);
        insertRelationship(connection, "rel_llm_consumes_context", "External LLM App", "CloudQueryX", "CONSUMES_CONTEXT_FROM",
                "External LLM apps call CloudQueryX to get formatted context, then call their chosen LLM separately.", stats);

        String[] layers = {"API_GATEWAY", "MEMORY_SERVICE", "SOURCE_INDEXER", "GRAPH_SERVICE", "EVENT_SERVICE",
                "RANKING_SERVICE", "BUNDLE_BUILDER", "TOKEN_OPTIMIZER", "FORMATTER", "SECURITY_FILTER",
                "PGVECTOR_INDEX", "FULL_TEXT_INDEX"};
        String[] relTypes = {"USES", "VALIDATES", "ENRICHES", "RANKS_WITH", "EMITS", "FETCHES_FROM", "SECURES",
                "FORMATS_FOR", "BUDGETS", "EXPLAINS"};
        for (int i = 0; i < 240; i++) {
            String type = layers[i % layers.length];
            String id = "ent_unique_component_" + i;
            String name = "CloudQueryX Unique " + type + " Component " + i;
            String desc = "Unique graph entity " + i + " for " + type
                    + " responsible for database-scoped context runtime behavior, retrieval planning, pgvector search, source chunk analysis, memory quality, and explainability path "
                    + UUID.nameUUIDFromBytes((id + type).getBytes(StandardCharsets.UTF_8));
            insertEntity(connection, id, type, name, desc, stats);
        }
        for (int i = 0; i < 720; i++) {
            String source = "CloudQueryX Unique " + layers[i % layers.length] + " Component " + (i % 240);
            String target = "CloudQueryX Unique " + layers[(i + 5) % layers.length] + " Component " + ((i * 7 + 13) % 240);
            String type = relTypes[i % relTypes.length];
            String desc = "Unique relationship " + i + " proves graph traversal can connect " + source + " to " + target
                    + " while preserving workspace isolation, relationship type " + type
                    + ", scoring evidence, event freshness, source references, and context bundle explanation id "
                    + UUID.nameUUIDFromBytes((source + target + type + i).getBytes(StandardCharsets.UTF_8));
            insertRelationship(connection, "rel_unique_" + i, source, target, type, desc, stats);
        }
    }

    private static Connection loadSources(Connection connection, LoadStats stats) throws SQLException {
        String[] types = {"document", "code", "log", "note", "config", "conversation", "markdown", "runbook"};
        long sourceTotalBytes = Math.min(32L * 1024L * 1024L, targetBytes / 16L);
        long perSourceBytes = sourceTotalBytes / SOURCE_COUNT;
        long chunkTargetBytes = (targetBytes - sourceTotalBytes) / SOURCE_COUNT;

        for (int i = 0; i < SOURCE_COUNT; i++) {
            String type = types[i % types.length];
            String sourceId = "src_prod_" + i;
            String sourceContent = buildContent(type + "-metadata", i, (int) perSourceBytes);
            String chunkContent = buildContent(type, i, (int) chunkTargetBytes);
            boolean loaded = false;
            int attempts = 0;
            while (!loaded) {
                attempts++;
                try {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SET TRANSACTION READ WRITE");
                    }
                    insertSource(connection, sourceId, type, "production-" + type + "-source-" + i + ".txt", sourceContent, stats);
                    insertChunks(connection, sourceId, type, chunkContent, stats);
                    connection.commit();
                    loaded = true;
                    System.out.println("loaded source " + (i + 1) + "/" + SOURCE_COUNT + " payloadMiB="
                            + String.format(Locale.ROOT, "%.1f", stats.bytes / 1024.0 / 1024.0));
                } catch (SQLException e) {
                    connection.rollback();
                    if (attempts >= 3 || !isReadOnlyOrConnectionIssue(e)) {
                        throw e;
                    }
                    System.out.println("reconnecting after writable transaction failure on source " + i + ": " + e.getMessage());
                    connection.close();
                    connection = connect();
                }
            }
        }
        return connection;
    }

    private static boolean isReadOnlyOrConnectionIssue(SQLException e) {
        String state = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "25006".equals(state)
                || "08006".equals(state)
                || message.contains("read-only transaction")
                || message.contains("connection reset");
    }

    private static void insertSource(Connection connection, String id, String type, String name, String content, LoadStats stats)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sources (id, database_id, user_id, source_type, source_name, content, content_hash, metadata, version, status)
                VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?::jsonb, 1, 'ACTIVE')
                """)) {
            statement.setString(1, id);
            statement.setString(2, DATABASE_ID);
            statement.setString(3, USER_ID);
            statement.setString(4, type);
            statement.setString(5, name);
            statement.setString(6, content);
            statement.setString(7, sha256(content));
            statement.setString(8, "{\"load\":\"500mb-current-test\",\"sourceType\":\"" + type + "\"}");
            statement.executeUpdate();
            stats.sources++;
            stats.bytes += content.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private static void insertChunks(Connection connection, String sourceId, String type, String content, LoadStats stats)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO context_chunks (id, source_id, database_id, chunk_index, content, token_estimate, content_hash, metadata)
                VALUES (?, ?, ?::uuid, ?, ?, ?, ?, ?::jsonb)
                """)) {
            int index = 0;
            for (int start = 0; start < content.length(); start += CHUNK_CHARS) {
                String chunk = content.substring(start, Math.min(content.length(), start + CHUNK_CHARS));
                statement.setString(1, sourceId + "_chunk_" + index);
                statement.setString(2, sourceId);
                statement.setString(3, DATABASE_ID);
                statement.setInt(4, index);
                statement.setString(5, chunk);
                statement.setInt(6, Math.max(1, chunk.length() / 4));
                statement.setString(7, sha256(chunk));
                statement.setString(8, "{\"sourceType\":\"" + type + "\",\"chunkIndex\":" + index + "}");
                statement.addBatch();
                stats.chunks++;
                stats.bytes += chunk.getBytes(StandardCharsets.UTF_8).length;
                index++;
                if (index % 100 == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private static void insertMemories(Connection connection, LoadStats stats) throws SQLException {
        String[] memoryTypes = {"FACT", "DECISION", "PREFERENCE", "CONVERSATION", "CORRECTION"};
        String[] topics = {"architecture", "retrieval", "pgvector", "source chunking", "knowledge graph", "events",
                "token budget", "database isolation", "API keys", "deployment", "debugging", "formatted context"};
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memories (id, database_id, user_id, namespace, memory_type, content, metadata, importance, confidence, recency, source, scope)
                VALUES (?, ?::uuid, ?, 'default', ?, ?, ?::jsonb, ?, 1.0, 1.0, '500mb-current-test', 'user')
                """)) {
            for (int i = 0; i < 2500; i++) {
                String topic = topics[i % topics.length];
                String content = "CloudQueryX " + topic + " memory " + i
                        + ": Website UI calls Java API Server, then Context Runtime combines Memory Engine recall, Source Store chunks, Knowledge Graph relationships, Event Store freshness, and Supabase PostgreSQL with pgvector. "
                        + "Unique memory fingerprint " + UUID.nameUUIDFromBytes(("memory-" + i + "-" + topic).getBytes(StandardCharsets.UTF_8))
                        + " records module " + topics[(i + 3) % topics.length]
                        + ", failure mode " + topics[(i + 7) % topics.length]
                        + ", production retrieval quality, ownership isolation, scoring, explainability, and context bundle formatting.";
                statement.setString(1, "mem_prod_" + i);
                statement.setString(2, DATABASE_ID);
                statement.setString(3, USER_ID);
                statement.setString(4, memoryTypes[i % memoryTypes.length]);
                statement.setString(5, content);
                statement.setString(6, "{\"topic\":\"" + topic + "\",\"load\":\"500mb-current-test\"}");
                statement.setDouble(7, i % 9 == 0 ? 0.95 : 0.72);
                statement.addBatch();
                stats.memories++;
                stats.bytes += content.getBytes(StandardCharsets.UTF_8).length;
                if (i % 200 == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private static void insertEvents(Connection connection, LoadStats stats) throws SQLException {
        String[] eventTypes = {"ARCHITECTURE_CONTEXT_DEFINED", "RETRIEVAL_TESTED", "RANKING_TUNED", "SOURCE_INDEXED",
                "GRAPH_EXPANDED", "TOKEN_BUDGET_APPLIED", "PGVECTOR_SEARCH_USED", "DATABASE_ISOLATION_VERIFIED"};
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO events (id, database_id, user_id, event_type, action, properties, session_id)
                VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, '500mb-current-test')
                """)) {
            for (int i = 0; i < 1500; i++) {
                String type = eventTypes[i % eventTypes.length];
                String action = type + " event " + i
                        + ": CloudQueryX production-scale test recorded architecture, memory, source, graph, relationship, event, pgvector, ownership, ranking, and context bundle behavior. Unique event fingerprint "
                        + UUID.nameUUIDFromBytes(("event-" + i + "-" + type).getBytes(StandardCharsets.UTF_8));
                statement.setString(1, "evt_prod_" + i);
                statement.setString(2, DATABASE_ID);
                statement.setString(3, USER_ID);
                statement.setString(4, type);
                statement.setString(5, action);
                statement.setString(6, "{\"load\":\"500mb-current-test\",\"eventIndex\":" + i + "}");
                statement.addBatch();
                stats.events++;
                stats.bytes += action.getBytes(StandardCharsets.UTF_8).length;
                if (i % 200 == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private static void insertEntity(Connection connection, String id, String type, String name, String description,
            LoadStats stats) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO entities (id, database_id, user_id, entity_type, name, description, attributes, confidence, source)
                VALUES (?, ?::uuid, ?, ?, ?, ?, ?::jsonb, 1.0, '500mb-current-test')
                """)) {
            statement.setString(1, id);
            statement.setString(2, DATABASE_ID);
            statement.setString(3, USER_ID);
            statement.setString(4, type);
            statement.setString(5, name);
            statement.setString(6, description);
            statement.setString(7, "{\"canonical\":true}");
            statement.executeUpdate();
            stats.entities++;
            stats.bytes += description.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private static void insertRelationship(Connection connection, String id, String source, String target, String type,
            String description, LoadStats stats) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO relationships (id, database_id, source_entity_id, target_entity_id, relationship_type, attributes, weight, confidence, source)
                VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, 1.0, 1.0, '500mb-current-test')
                """)) {
            statement.setString(1, id);
            statement.setString(2, DATABASE_ID);
            statement.setString(3, source);
            statement.setString(4, target);
            statement.setString(5, type);
            statement.setString(6, "{\"description\":\"" + json(description) + "\",\"canonical\":true}");
            statement.executeUpdate();
            stats.relationships++;
            stats.bytes += description.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private static String buildContent(String type, int sourceIndex, int targetBytes) {
        StringBuilder builder = new StringBuilder(targetBytes + 4096);
        builder.append("# CloudQueryX production ").append(type).append(" source ").append(sourceIndex).append('\n');
        builder.append("Summary: CloudQueryX is a provider-neutral Context Runtime and Query Planner for LLM applications. ");
        builder.append("Architecture: Website UI sends authenticated requests to the Java API Server. ");
        builder.append("The API Server calls the Context Runtime, which combines Memory Engine recall, Source Store chunk retrieval, Knowledge Graph entities and relationships, Event Store freshness, and pgvector similarity search. ");
        builder.append("Storage is Supabase PostgreSQL with pgvector. Retrieval ranks memories, source chunks, graph relationships, events, full-text matches, vector matches, freshness, importance, and token budget decisions.\\n\n");

        int detail = 0;
        while (builder.length() < targetBytes) {
            String mode = switch (detail % 7) {
                case 0 -> "architecture";
                case 1 -> "debugging deployment DATABASE_URL connection pool HikariCP Supabase";
                case 2 -> "pgvector embedding semantic similarity vector retrieval";
                case 3 -> "knowledge graph entity relationship traversal";
                case 4 -> "event freshness timeline recent architecture changes";
                case 5 -> "token budget context bundle formatted context external LLM";
                default -> "security database ownership API key workspace isolation";
            };
            builder.append("Detail ").append(detail).append(" for ").append(type).append(": ");
            String fingerprint = UUID.nameUUIDFromBytes((type + "-" + sourceIndex + "-" + detail + "-" + mode)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            builder.append(mode).append(" context explains unique source ")
                    .append(sourceIndex).append(" detail ").append(detail)
                    .append(" with fingerprint ").append(fingerprint)
                    .append(". It covers CloudQueryX memory recall, source chunking, graph traversal, event freshness, pgvector similarity, full-text search, explainability, production ranking, provider-neutral formatted context, database API key scoping, and query planner behavior. ");
            if ("code".equals(type)) {
                builder.append("class ContextBundleBuilder").append(sourceIndex).append("_").append(detail)
                        .append(" { retrieveCandidates(); rankByHybridScore(); optimizeTokenBudget(); formatContext(); } ");
            } else if ("log".equals(type)) {
                builder.append("INFO retrieval trace trace_id=").append(fingerprint)
                        .append(" candidate_count=").append(80 + (detail % 400))
                        .append(" source=memory+source+graph+events vector=pgvector text=tsvector latency_ms=")
                        .append(10 + (detail % 90)).append(' ');
            } else if ("config".equals(type)) {
                builder.append("CLOUDQUERYX_EMBEDDING_PROVIDER=local CLOUDQUERYX_VECTOR_DIMENSION=384 CLOUDQUERYX_DATABASE_SCOPE=required UNIQUE_CONFIG_KEY_")
                        .append(sourceIndex).append('_').append(detail).append('=').append(fingerprint).append(' ');
            }
            builder.append('\n');
            detail++;
        }
        return builder.substring(0, targetBytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + key + " in .env");
        }
        return value;
    }

    private static Map<String, String> readEnv(Path path) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            String key = trimmed.substring(0, trimmed.indexOf('=')).trim();
            String value = trimmed.substring(trimmed.indexOf('=') + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }

    private static final class LoadStats {
        long bytes;
        int sources;
        int chunks;
        int memories;
        int entities;
        int relationships;
        int events;
    }
}
