package com.cloudqueryx.web.api;

import com.cloudqueryx.config.AppConfig;
import com.cloudqueryx.context.ContextEngine;
import com.cloudqueryx.context.ContextRecallResult;
import com.cloudqueryx.context.runtime.*;
import com.cloudqueryx.embedding.*;
import com.cloudqueryx.llm.OpenAiChatService;
import com.cloudqueryx.repository.*;
import com.cloudqueryx.storage.S3ProjectFileStorage;
import com.cloudqueryx.web.auth.SupabaseJwtValidator;
import com.cloudqueryx.webhook.WebhookDispatcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

public class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

    private final HttpServer server;
    private final AppConfig config;
    private final DatabaseConfig providedDbConfig;
    private DatabaseConfig dbConfig;
    private UserRepository userRepo;
    private SessionRepository sessionRepo;
    private DatabaseRepository databaseRepo;
    private DatabaseApiKeyRepository apiKeyRepo;
    private CodingProjectRepository codingProjectRepo;
    private VectorRepository vectorRepo;
    private MemoryRepository memoryRepo;
    private GraphRepository graphRepo;
    private EventRepository eventRepo;
    private EmbeddingService embeddingService;
    private WebhookDispatcher webhookDispatcher;
    private SourceService sourceService;
    private ContextRetrievalService contextRetrievalService;
    private ContextBundleService contextBundleService;
    private OpenAiChatService openAiChatService;
    private S3ProjectFileStorage projectFileStorage;
    private SupabaseJwtValidator supabaseJwtValidator;
    private volatile boolean schemaMigrationStarted;
    private volatile boolean backendReady;
    private volatile Throwable startupError;

    public ApiServer(int port) throws IOException {
        this.config = AppConfig.getInstance();
        this.providedDbConfig = null;

        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(httpThreadPoolSize()));
        registerRoutes();
        log.info("API server configured on port {}", port);
    }

    public ApiServer(int port, DatabaseConfig dbConfig) throws IOException {
        this.config = AppConfig.getInstance();
        this.providedDbConfig = dbConfig;
        this.dbConfig = dbConfig;
        initializeBackend(dbConfig);
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(httpThreadPoolSize()));
        registerRoutes();
        log.info("API server configured on port {} (custom db config)", port);
    }

    private void initializeBackend(DatabaseConfig databaseConfig) {
        Duration sessionTimeout = Duration.ofHours(config.sessionTimeoutHours());

        this.dbConfig = databaseConfig;
        this.userRepo = new UserRepository(databaseConfig);
        this.sessionRepo = new SessionRepository(databaseConfig, sessionTimeout);
        this.databaseRepo = new DatabaseRepository(databaseConfig);
        this.apiKeyRepo = new DatabaseApiKeyRepository(databaseConfig);
        this.codingProjectRepo = new CodingProjectRepository(databaseConfig);
        this.vectorRepo = new VectorRepository(databaseConfig);
        this.memoryRepo = new MemoryRepository(databaseConfig);
        this.graphRepo = new GraphRepository(databaseConfig);
        this.eventRepo = new EventRepository(databaseConfig);
        this.embeddingService = createEmbeddingService();
        this.webhookDispatcher = new WebhookDispatcher(new WebhookRepository(databaseConfig));
        SourceRepository sourceRepo = new SourceRepository(databaseConfig);
        ContextChunkRepository chunkRepo = new ContextChunkRepository(databaseConfig);
        ContextBundleRepository bundleRepo = new ContextBundleRepository(databaseConfig);
        TokenEstimator tokenEstimator = new TokenEstimator();
        this.sourceService = new SourceService(sourceRepo, chunkRepo, new ChunkingService(tokenEstimator), embeddingService);
        this.contextRetrievalService = new ContextRetrievalService(
                memoryRepo, chunkRepo, graphRepo, eventRepo, embeddingService, tokenEstimator);
        this.contextBundleService = new ContextBundleService(
                contextRetrievalService, bundleRepo,
                new TokenBudgetOptimizer(tokenEstimator, new ExtractiveContextCompressor(tokenEstimator)),
                new ContextFormatterService());
        this.openAiChatService = new OpenAiChatService(config);
        this.projectFileStorage = new S3ProjectFileStorage(config);
        this.supabaseJwtValidator = new SupabaseJwtValidator(config);
        this.backendReady = true;
        this.startupError = null;
        log.info("Backend services initialized");
    }

    private int httpThreadPoolSize() {
        int configured = config.dbPoolSize();
        if (System.getenv("VERCEL") != null) {
            return Math.max(2, Math.min(configured, 4));
        }
        return Math.max(2, configured);
    }

    private void startBackendInitialization() {
        if (backendReady || startupError != null || providedDbConfig != null) return;
        Thread init = new Thread(() -> {
            try {
                initializeBackend(DatabaseConfig.getInstance());
                runSchemaMigrationAsync();
            } catch (Throwable t) {
                startupError = t;
                log.error("Backend initialization failed: {}", t.getMessage(), t);
            }
        }, "cloudqueryx-backend-init");
        init.setDaemon(true);
        init.start();
    }

    private EmbeddingService createEmbeddingService() {
        String provider = config.embeddingProvider();
        try {
            EmbeddingService base = switch (provider.toLowerCase()) {
                case "local" -> {
                    Path modelDir = Path.of(config.modelDir());
                    ModelDownloader.ensureModel(modelDir);
                    yield new LocalEmbeddingService(modelDir);
                }
                case "openai" -> new OpenAIEmbeddingService(
                        config.openaiApiKey(), config.openaiModel(), config.vectorDimension());
                case "none" -> {
                    log.info("Embedding service disabled (provider=none)");
                    yield null;
                }
                default -> {
                    log.warn("Unknown embedding provider '{}', running without embeddings", provider);
                    yield null;
                }
            };
            if (base != null) {
                int cacheSize = config.getInt("CLOUDQUERYX_EMBEDDING_CACHE_SIZE", 2048);
                return new CachingEmbeddingService(base, cacheSize);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to initialize embedding service: {}. Running without embeddings.", e.getMessage());
            return null;
        }
    }

    public void start() {
        server.start();
        log.info("API server started on http://localhost:{}", server.getAddress().getPort());
        startBackendInitialization();
    }

    private void runSchemaMigrationAsync() {
        if (schemaMigrationStarted) return;
        schemaMigrationStarted = true;
        Thread migration = new Thread(() -> SchemaManager.runMigration(dbConfig, "/migration-v2.sql"),
                "cloudqueryx-schema-migration");
        migration.setDaemon(true);
        migration.start();
    }

    public void stop() {
        server.stop(1);
        if (dbConfig != null) {
            dbConfig.close();
        }
    }

    private void registerRoutes() {
        server.createContext("/", this::serveStatic);
        server.createContext("/api/demo/start", ex -> wrap(ex, this::handleDemoStart));
        server.createContext("/api/demo/end", ex -> wrap(ex, this::handleDemoEnd));
        server.createContext("/api/auth/signup", ex -> wrap(ex, this::handleSignup));
        server.createContext("/api/auth/login", ex -> wrap(ex, this::handleLogin));
        server.createContext("/api/auth/logout", ex -> wrap(ex, this::handleLogout));
        server.createContext("/api/auth/me", ex -> wrap(ex, this::handleMe));
        server.createContext("/api/config/public", ex -> wrap(ex, this::handlePublicConfig));
        server.createContext("/api/databases", ex -> wrap(ex, this::handleDatabases));
        server.createContext("/api/code/projects", ex -> wrap(ex, this::handleCodingProjects));
        server.createContext("/api/sources", ex -> wrap(ex, this::handleSources));
        server.createContext("/api/context/retrieve", ex -> wrap(ex, this::handleContextRetrieve));
        server.createContext("/api/context/build", ex -> wrap(ex, this::handleContextBuild));
        server.createContext("/api/context/bundles", ex -> wrap(ex, this::handleContextBundleExplain));
        server.createContext("/api/context", ex -> wrap(ex, this::handleContext));
        server.createContext("/api/assistant/chat", ex -> wrap(ex, this::handleAssistantChat));
        server.createContext("/api/memory", ex -> wrap(ex, this::handleMemory));
        server.createContext("/api/v1/store", ex -> wrap(ex, this::handleExternalStore));
        server.createContext("/api/v1/recall", ex -> wrap(ex, this::handleExternalRecall));
        server.createContext("/api/v1/retrieve", ex -> wrap(ex, this::handleExternalRetrieve));
        server.createContext("/api/v1/context/build", ex -> wrap(ex, this::handleExternalContextBuild));
        server.createContext("/api/vectors", ex -> wrap(ex, this::handleVectors));
        server.createContext("/api/semantic", ex -> wrap(ex, this::handleSemantic));
        server.createContext("/api/events", ex -> wrap(ex, this::handleEvents));
        server.createContext("/api/webhooks", ex -> wrap(ex, this::handleWebhooks));
        server.createContext("/api/health", ex -> wrap(ex, this::handleHealth));
        server.createContext("/api/bulk", ex -> wrap(ex, this::handleBulkIngest));
    }

    private void wrap(HttpExchange ex, IOHandler handler) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!isPublicStartupPath(ex) && !backendReady) {
            sendJson(ex, 503, Map.of(
                    "error", startupError == null ? "CloudQueryX backend is starting" : "CloudQueryX backend failed to start",
                    "status", startupError == null ? "starting" : "unhealthy"));
            return;
        }
        try {
            handler.handle(ex);
        } catch (Exception e) {
            log.error("Request handler error: {}", e.getMessage(), e);
            if (!ex.getResponseHeaders().containsKey("Content-Type")) {
                sendError(ex, 500, "Internal server error");
            }
        }
    }

    private boolean isPublicStartupPath(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        return "/".equals(path) || path.startsWith("/assets/") || path.startsWith("/api/health")
                || path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".png")
                || path.endsWith(".ico") || path.endsWith(".svg");
    }

    private void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Content-Type, Authorization, X-Database-Id");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, OPTIONS");
    }

    @FunctionalInterface
    private interface IOHandler {
        void handle(HttpExchange ex) throws IOException;
    }

    // ─── Health ────────────────────────────────────────────────────────

    private void handleHealth(HttpExchange ex) throws IOException {
        if (!backendReady) {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", startupError == null ? "starting" : "unhealthy");
            health.put("database", "not_ready");
            if (startupError != null) {
                health.put("error", startupError.getMessage());
            }
            sendJson(ex, startupError == null ? 202 : 503, health);
            return;
        }
        try (var conn = dbConfig.getConnection()) {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", "healthy");
            health.put("database", "connected");
            health.put("embedding", embeddingService != null ? "active (" + config.embeddingProvider() + ")" : "disabled");
            if (embeddingService instanceof CachingEmbeddingService cache) {
                health.put("embeddingCache", Map.of(
                        "size", cache.size(), "hits", cache.hits(), "misses", cache.misses()));
            }
            health.put("features", List.of("conflict-resolution", "memory-scoping",
                    "temporal-queries", "full-text-search", "webhooks", "embedding-cache"));
            sendJson(ex, 200, health);
        } catch (Exception e) {
            sendJson(ex, 503, Map.of("status", "unhealthy", "database", "disconnected",
                    "error", e.getMessage()));
        }
    }

    private void handlePublicConfig(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("supabaseUrl", config.supabaseUrl());
        response.put("supabaseAnonKey", config.supabaseAnonKey());
        response.put("supabaseAuthEnabled", config.supabaseUrl() != null
                && config.supabaseAnonKey() != null
                && config.supabaseJwtSecret() != null);
        response.put("s3FileStorageEnabled", config.s3Enabled());
        sendJson(ex, 200, response);
    }

    // ─── Auth ──────────────────────────────────────────────────────────

    private void handleDemoStart(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = "demo-" + suffix + "@cloudqueryx.local";
        String password = UUID.randomUUID().toString() + UUID.randomUUID();
        UserRepository.UserRow user = userRepo.register(email, password);
        DatabaseRepository.DatabaseRow db = databaseRepo.create(user.id(), "Temporary Demo Context",
                "Temporary context database. It is deleted when the demo session ends.");
        String token = sessionRepo.createSession(user.id(), user.email());
        Map<String, Object> response = authResponse(token, user, db);
        response.put("demo", true);
        response.put("limits", Map.of(
                "messages", 20,
                "contextWrites", 60,
                "retention", "Deleted when you close the tab, leave the page, or end the demo."));
        sendJson(ex, 201, response);
    }

    private void handleDemoEnd(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        String token = getToken(ex);
        if (token == null) {
            Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
            token = JsonUtil.getString(body, "token");
        }
        if (token == null || token.isBlank()) {
            sendJson(ex, 200, Map.of("message", "No demo session to clean up"));
            return;
        }
        Optional<SessionRepository.SessionRow> session = sessionRepo.validate(token);
        if (session.isPresent() && session.get().email().startsWith("demo-")
                && session.get().email().endsWith("@cloudqueryx.local")) {
            int s3Deleted = 0;
            try {
                s3Deleted = projectFileStorage.deleteUserPrefix(session.get().userId());
            } catch (Exception e) {
                log.warn("Failed to delete demo S3 objects for user {}", session.get().userId(), e);
            }
            databaseRepo.listForUser(session.get().userId()).forEach(db ->
                    databaseRepo.delete(db.id(), session.get().userId()));
            sessionRepo.invalidate(token);
            sendJson(ex, 200, Map.of("message", "Demo data deleted", "s3ObjectsDeleted", s3Deleted));
            return;
        }
        sessionRepo.invalidate(token);
        sendJson(ex, 200, Map.of("message", "Demo data deleted"));
    }

    private void handleSignup(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        try {
            Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
            String email = JsonUtil.getString(body, "email");
            String password = JsonUtil.getString(body, "password");
            if (email == null || password == null || password.length() < 8) {
                sendError(ex, 400, "Email and password (min 8 chars) required");
                return;
            }
            UserRepository.UserRow user = userRepo.register(email, password);
            DatabaseRepository.DatabaseRow db = databaseRepo.ensureDefaultForUser(user.id());
            String token = sessionRepo.createSession(user.id(), user.email());
            sendJson(ex, 201, authResponse(token, user, db));
        } catch (IllegalArgumentException e) {
            sendError(ex, 409, e.getMessage());
        }
    }

    private void handleLogin(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        String email = JsonUtil.getString(body, "email");
        String password = JsonUtil.getString(body, "password");
        Optional<UserRepository.UserRow> user = userRepo.authenticate(email, password);
        if (user.isEmpty()) {
            sendError(ex, 401, "Invalid credentials");
            return;
        }
        String token = sessionRepo.createSession(user.get().id(), user.get().email());
        DatabaseRepository.DatabaseRow db = databaseRepo.ensureDefaultForUser(user.get().id());
        sendJson(ex, 200, authResponse(token, user.get(), db));
    }

    private void handleLogout(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        String token = getToken(ex);
        if (token != null) sessionRepo.invalidate(token);
        sendJson(ex, 200, Map.of("message", "Logged out"));
    }

    private void handleMe(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        Optional<UserRepository.UserRow> user = userRepo.getById(session.userId());
        if (user.isEmpty()) {
            sendError(ex, 404, "User not found");
            return;
        }
        DatabaseRepository.DatabaseRow db = databaseRepo.ensureDefaultForUser(user.get().id());
        Map<String, Object> response = new LinkedHashMap<>(user.get().toPublicMap());
        response.put("defaultDatabase", databaseToMap(db));
        sendJson(ex, 200, response);
    }

    private Map<String, Object> authResponse(String token, UserRepository.UserRow user,
                                             DatabaseRepository.DatabaseRow db) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("user", user.toPublicMap());
        response.put("defaultDatabase", databaseToMap(db));
        return response;
    }

    // ─── Databases ─────────────────────────────────────────────────────

    private void handleDatabases(HttpExchange ex) throws IOException {
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;

        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String[] segments = path.split("/");

        if ("GET".equals(method) && segments.length <= 3) {
            DatabaseRepository.DatabaseRow db = databaseRepo.ensureDefaultForUser(session.userId());
            sendJson(ex, 200, Map.of("databases", List.of(databaseToMap(db))));
        } else if (segments.length >= 5 && "api-keys".equals(segments[4])) {
            handleDatabaseApiKeys(ex, session, segments);
        } else if ("POST".equals(method)) {
            sendError(ex, 403, "CloudQueryX creates one private context database automatically for each user.");
        } else if ("DELETE".equals(method) && segments.length >= 4) {
            sendError(ex, 403, "Deleting the user's private context database is disabled in the demo product.");
        } else {
            sendError(ex, 405, "Method not allowed");
        }
    }

    private void handleDatabaseApiKeys(HttpExchange ex, SessionRepository.SessionRow session,
                                       String[] segments) throws IOException {
        String dbId = segments[3];
        if (databaseRepo.get(dbId, session.userId()).isEmpty()) {
            sendError(ex, 404, "Database not found or access denied");
            return;
        }

        String method = ex.getRequestMethod();
        if ("GET".equals(method) && segments.length == 5) {
            List<Map<String, Object>> keys = apiKeyRepo.list(dbId, session.userId()).stream()
                    .map(this::apiKeyToMap)
                    .toList();
            sendJson(ex, 200, Map.of("apiKeys", keys));
            return;
        }

        if ("POST".equals(method) && segments.length == 5) {
            Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
            DatabaseApiKeyRepository.CreatedApiKey created = apiKeyRepo.create(
                    dbId, session.userId(), Objects.requireNonNullElse(JsonUtil.getString(body, "name"), "Dashboard key"));
            Map<String, Object> response = new LinkedHashMap<>(apiKeyToMap(created.key()));
            response.put("rawKey", created.rawKey());
            response.put("message", "Copy this key now. It will not be shown again.");
            sendJson(ex, 201, response);
            return;
        }

        if ("DELETE".equals(method) && segments.length >= 6) {
            boolean revoked = apiKeyRepo.revoke(dbId, session.userId(), segments[5]);
            if (revoked) sendJson(ex, 200, Map.of("message", "API key revoked"));
            else sendError(ex, 404, "API key not found");
            return;
        }

        sendError(ex, 405, "Method not allowed");
    }

    // ─── Unified Context API ───────────────────────────────────────────

    private void handleCodingProjects(HttpExchange ex) throws IOException {
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String prefix = "/api/code/projects";

        try {
            if ("GET".equals(method) && prefix.equals(path)) {
                List<Map<String, Object>> projects = codingProjectRepo.list(dbId, session.userId(), 100).stream()
                        .map(this::codingProjectToMap)
                        .toList();
                sendJson(ex, 200, Map.of("projects", projects, "count", projects.size()));
                return;
            }

            if ("POST".equals(method) && prefix.equals(path)) {
                Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
                CodingProjectRepository.ProjectRow project = codingProjectRepo.create(
                        dbId,
                        session.userId(),
                        requiredBodyString(body, "name"),
                        JsonUtil.getString(body, "description"),
                        Objects.requireNonNullElse(JsonUtil.getString(body, "sourceType"), "upload"),
                        JsonUtil.getString(body, "githubRepoUrl"));
                sendJson(ex, 201, codingProjectToMap(project));
                return;
            }

            if (!path.startsWith(prefix + "/")) {
                sendError(ex, 404, "Coding project route not found");
                return;
            }

            String[] parts = path.substring((prefix + "/").length()).split("/");
            if (parts.length < 1 || parts[0].isBlank()) {
                sendError(ex, 404, "Coding project route not found");
                return;
            }

            String projectId = parts[0];
            Optional<CodingProjectRepository.ProjectRow> project = codingProjectRepo.get(projectId, dbId, session.userId());
            if (project.isEmpty()) {
                sendError(ex, 404, "Coding project not found");
                return;
            }

            if (parts.length == 1 && "PUT".equals(method)) {
                Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
                CodingProjectRepository.ProjectRow updated = codingProjectRepo.update(
                        projectId,
                        dbId,
                        session.userId(),
                        requiredBodyString(body, "name"),
                        JsonUtil.getString(body, "description"));
                sendJson(ex, 200, codingProjectToMap(updated));
                return;
            }

            if (parts.length < 2) {
                sendError(ex, 404, "Coding project route not found");
                return;
            }

            if ("files".equals(parts[1])) {
                handleProjectFiles(ex, session, dbId, project.get());
                return;
            }

            if ("ask".equals(parts[1]) && "POST".equals(method)) {
                handleCodingAsk(ex, session, dbId, project.get());
                return;
            }

            sendError(ex, 405, "Method not allowed");
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
        } catch (IllegalStateException e) {
            sendError(ex, 503, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(ex, 504, "OpenAI request interrupted");
        }
    }

    private void handleProjectFiles(HttpExchange ex, SessionRepository.SessionRow session, String dbId,
                                    CodingProjectRepository.ProjectRow project)
            throws IOException {
        if ("GET".equals(ex.getRequestMethod())) {
            List<Map<String, Object>> files = codingProjectRepo.listFiles(project.id(), dbId, session.userId()).stream()
                    .map(this::projectFileToMap)
                    .toList();
            sendJson(ex, 200, Map.of("files", files, "count", files.size()));
            return;
        }

        if (!"POST".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }

        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        String filePath = CodingProjectRepository.normalizePath(requiredBodyString(body, "path"));
        String content = requiredBodyString(body, "content");
        String language = Objects.requireNonNullElse(JsonUtil.getString(body, "language"), inferLanguage(filePath));
        String contentHash = sha256(content);
        int nextVersion = codingProjectRepo.listFiles(project.id(), dbId, session.userId()).stream()
                .filter(f -> f.path().equals(filePath))
                .mapToInt(CodingProjectRepository.FileRow::version)
                .max()
                .orElse(0) + 1;
        String s3Key = projectFileStorage.keyFor(session.userId(), project.id(), nextVersion, filePath);
        projectFileStorage.putText(s3Key, content, contentTypeFor(language));

        SourceRepository.SourceRow source = sourceService.create(
                dbId,
                session.userId(),
                sourceTypeForLanguage(language),
                filePath,
                content,
                Map.of(
                        "origin", "coding-assistant",
                        "projectId", project.id(),
                        "path", filePath,
                        "language", language,
                        "s3Key", s3Key));

        CodingProjectRepository.FileRow file = codingProjectRepo.upsertFile(
                project.id(), dbId, session.userId(), source.id(), filePath, language, s3Key,
                contentHash, content.getBytes(StandardCharsets.UTF_8).length);

        sendJson(ex, 201, Map.of("file", projectFileToMap(file), "source", sourceToMap(source, false)));
    }

    private void handleCodingAsk(HttpExchange ex, SessionRepository.SessionRow session, String dbId,
                                 CodingProjectRepository.ProjectRow project)
            throws IOException, InterruptedException {
        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        String task = requiredBodyString(body, "task");
        List<CodingProjectRepository.FileRow> files = codingProjectRepo.listFiles(project.id(), dbId, session.userId());
        String fileList = files.stream()
                .limit(80)
                .map(f -> "- " + f.path() + " (" + f.language() + ", v" + f.version() + ")")
                .reduce("", (a, b) -> a + b + "\n");
        String message = """
                CODING_ASSISTANT_TASK:
                %s

                CLOUD_PROJECT:
                %s

                FILES_AVAILABLE:
                %s

                Behave as a production cloud coding assistant.
                Required response sections:
                1. Context selected: name the files, memories, events, or sources that matter.
                2. Diagnosis: explain the likely cause using only available context.
                3. Plan: list the smallest safe change.
                4. Risks: mention what could break.
                5. Verification: give exact tests or checks to run.
                6. Patch strategy: describe changes before editing. Do not claim files were changed.
                If context is insufficient, say what file or log is missing instead of guessing.
                """.formatted(task, project.name(), fileList);

        ContextBundleService.BundleBuildResult bundle = contextBundleService.build(
                dbId,
                session.userId(),
                message,
                "medium-context-model",
                JsonUtil.getInt(body, "tokenBudget", 12000),
                "coding",
                true,
                List.of("code", "config", "log", "markdown", "document", "note"),
                true,
                true,
                true,
                true);

        OpenAiChatService.ChatResult chat = openAiChatService.chat(message, bundle);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", chat.answer());
        response.put("model", chat.model());
        response.put("usedContext", chat.usedContext());
        response.put("memorySuggestions", chat.memorySuggestions());
        response.put("contextBundle", bundleToMap(bundle));
        response.put("project", codingProjectToMap(project));
        sendJson(ex, 200, response);
    }

    // Context Runtime: Sources and Bundles

    private void handleSources(HttpExchange ex) throws IOException {
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if ("POST".equals(method) && "/api/sources".equals(path)) {
            Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
            try {
                SourceRepository.SourceRow source = sourceService.create(
                        dbId, session.userId(),
                        requiredBodyString(body, "sourceType"),
                        requiredBodyString(body, "sourceName"),
                        requiredBodyString(body, "content"),
                        JsonUtil.getMap(body, "metadata"));
                sendJson(ex, 201, sourceToMap(source, false));
            } catch (IllegalArgumentException e) {
                sendError(ex, 400, e.getMessage());
            }
            return;
        }

        if ("GET".equals(method) && "/api/sources".equals(path)) {
            List<Map<String, Object>> items = sourceService.list(dbId, session.userId(), 100).stream()
                    .map(s -> sourceToMap(s, false))
                    .toList();
            sendJson(ex, 200, Map.of("sources", items, "count", items.size()));
            return;
        }

        if ("GET".equals(method) && path.startsWith("/api/sources/")) {
            String id = path.substring("/api/sources/".length());
            Optional<SourceRepository.SourceRow> source = sourceService.get(dbId, id);
            if (source.isEmpty() || !session.userId().equals(source.get().userId())) {
                sendError(ex, 404, "Source not found");
                return;
            }
            sendJson(ex, 200, sourceToMap(source.get(), true));
            return;
        }

        sendError(ex, 405, "Method not allowed");
    }

    private void handleContextRetrieve(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        try {
            List<RetrievalResult> results = contextRetrievalService.retrieve(
                    dbId, session.userId(), requiredBodyString(body, "query"),
                    JsonUtil.getInt(body, "topK", 10),
                    stringList(body.get("sourceTypes")),
                    JsonUtil.getBoolean(body, "includeMemories", true),
                    JsonUtil.getBoolean(body, "includeSources", true),
                    JsonUtil.getBoolean(body, "includeGraph", true),
                    JsonUtil.getBoolean(body, "includeEvents", true));
            sendJson(ex, 200, Map.of("results", results.stream().map(this::retrievalToMap).toList()));
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    private void handleContextBuild(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        try {
            ContextBundleService.BundleBuildResult result = contextBundleService.build(
                    dbId, session.userId(), requiredBodyString(body, "query"),
                    Objects.requireNonNullElse(JsonUtil.getString(body, "targetModel"), "medium-context-model"),
                    JsonUtil.getInt(body, "tokenBudget", 8000),
                    Objects.requireNonNullElse(JsonUtil.getString(body, "mode"), "general"),
                    JsonUtil.getBoolean(body, "includeExplanations", true),
                    stringList(body.get("sourceTypes")),
                    JsonUtil.getBoolean(body, "includeMemories", true),
                    JsonUtil.getBoolean(body, "includeSources", true),
                    JsonUtil.getBoolean(body, "includeGraph", true),
                    JsonUtil.getBoolean(body, "includeEvents", true));
            sendJson(ex, 201, result);
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    private void handleContextBundleExplain(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        String prefix = "/api/context/bundles/";
        String suffix = "/explain";
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            sendError(ex, 404, "Bundle explanation route not found");
            return;
        }
        String bundleId = path.substring(prefix.length(), path.length() - suffix.length());
        Optional<ContextBundleRepository.BundleWithItems> bundle = contextBundleService.get(dbId, bundleId);
        if (bundle.isEmpty() || !session.userId().equals(bundle.get().bundle().userId())) {
            sendError(ex, 404, "Bundle not found");
            return;
        }

        List<Map<String, Object>> items = bundle.get().items().stream()
                .map(this::bundleItemToMap)
                .toList();
        sendJson(ex, 200, Map.of(
                "contextBundleId", bundleId,
                "query", bundle.get().bundle().query(),
                "targetModel", bundle.get().bundle().targetModel(),
                "estimatedTokens", bundle.get().bundle().estimatedTokens(),
                "freshnessStatus", bundle.get().bundle().freshnessStatus(),
                "items", items));
    }

    private void handleAssistantChat(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        String message = requiredBodyString(body, "message");
        try {
            ContextBundleService.BundleBuildResult bundle = contextBundleService.build(
                    dbId, session.userId(), message,
                    Objects.requireNonNullElse(JsonUtil.getString(body, "targetModel"), "medium-context-model"),
                    JsonUtil.getInt(body, "tokenBudget", config.chatTokenBudget()),
                    Objects.requireNonNullElse(JsonUtil.getString(body, "mode"), "general"),
                    true,
                    sourceTypeFilters(body),
                    JsonUtil.getBoolean(body, "includeMemories", true),
                    JsonUtil.getBoolean(body, "includeSources", true),
                    JsonUtil.getBoolean(body, "includeGraph", true),
                    JsonUtil.getBoolean(body, "includeEvents", true));

            OpenAiChatService.ChatResult chat = openAiChatService.chat(message, bundle);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("answer", chat.answer());
            response.put("model", chat.model());
            response.put("usedContext", chat.usedContext());
            response.put("memorySuggestions", chat.memorySuggestions());
            response.put("contextBundle", bundleToMap(bundle));
            sendJson(ex, 200, response);
        } catch (IllegalStateException e) {
            sendError(ex, 503, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(ex, 504, "OpenAI request interrupted");
        } catch (IOException e) {
            sendError(ex, 502, e.getMessage());
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    private void handleContext(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        ContextEngine engine = new ContextEngine(dbId, vectorRepo, memoryRepo, graphRepo, eventRepo, embeddingService, webhookDispatcher);

        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        String action = JsonUtil.getString(body, "action");
        if (action == null || action.isBlank()) {
            sendError(ex, 400, "action required: store, recall, relate, learn, or forget");
            return;
        }

        try {
            switch (action.toLowerCase()) {
                case "store" -> {
                    Map<String, Object> stored = engine.store(session.userId(), body);
                    sendJson(ex, 201, Map.of("stored", stored));
                }
                case "recall" -> {
                    List<ContextRecallResult> results = engine.recall(session.userId(), body);
                    List<Map<String, Object>> items = results.stream()
                            .map(r -> Map.<String, Object>of(
                                    "id", r.id(),
                                    "source", r.source(),
                                    "type", r.type(),
                                    "content", r.content() != null ? r.content() : "",
                                    "score", r.score(),
                                    "metadata", r.metadata(),
                                    "explanation", r.explanation()
                            ))
                            .toList();
                    sendJson(ex, 200, Map.of("results", items, "count", items.size()));
                }
                case "relate" -> {
                    Map<String, Object> related = engine.relate(session.userId(), body);
                    sendJson(ex, 201, Map.of("relationship", related));
                }
                case "learn" -> {
                    Map<String, Object> event = engine.learn(session.userId(), body);
                    sendJson(ex, 201, Map.of("event", event));
                }
                case "forget" -> {
                    sendError(ex, 403, "Manual context deletion is disabled in the demo product.");
                }
                default -> sendError(ex, 400, "Unknown context action: " + action);
            }
        } catch (Exception e) {
            log.error("Context API error", e);
            sendError(ex, 400, "Context error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    // ─── Memory ────────────────────────────────────────────────────────

    private void handleMemory(HttpExchange ex) throws IOException {
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        String method = ex.getRequestMethod();

        if ("POST".equals(method)) {
            Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
            String action = JsonUtil.getString(body, "action");

            if ("store".equals(action)) {
                String content = JsonUtil.getString(body, "content");
                String type = JsonUtil.getString(body, "type");
                if (type == null) type = "FACT";
                double importance = JsonUtil.getDouble(body, "importance", 0.5);
                float[] embedding = parseEmbedding(body.get("embedding"));
                if (embedding == null && embeddingService != null && content != null) {
                    embedding = embeddingService.embed(content);
                }
                String id = UUID.randomUUID().toString();
                String scope = JsonUtil.getString(body, "scope");
                if (scope == null) scope = "user";
                MemoryRepository.MemoryRow row = new MemoryRepository.MemoryRow(
                        id, session.userId(), "default", type.toUpperCase(), content,
                        JsonUtil.getMap(body, "metadata"), embedding,
                        importance, 1.0, 1.0, JsonUtil.getString(body, "source"),
                        0, null, null, null, null, scope
                );
                memoryRepo.upsert(dbId, row);
                sendJson(ex, 201, Map.of("id", id, "type", type.toUpperCase()));

            } else if ("recall".equals(action)) {
                float[] embedding = parseEmbedding(body.get("embedding"));
                if (embedding == null && embeddingService != null) {
                    String query = JsonUtil.getString(body, "query");
                    if (query == null) query = JsonUtil.getString(body, "content");
                    if (query != null && !query.isBlank()) {
                        embedding = embeddingService.embed(query);
                    }
                }
                if (embedding == null) {
                    sendError(ex, 400, "Provide 'query' text or 'embedding' vector");
                    return;
                }
                int topK = JsonUtil.getInt(body, "topK", 10);
                String typeFilter = JsonUtil.getString(body, "type");
                List<MemoryRepository.MemorySearchRow> results = memoryRepo.searchSimilar(
                        dbId, "default", embedding, topK, typeFilter, session.userId());
                List<Map<String, Object>> items = results.stream()
                        .map(r -> Map.<String, Object>of(
                                "id", r.row().id(),
                                "content", r.row().content() != null ? r.row().content() : "",
                                "type", r.row().memoryType(),
                                "similarity", r.similarity(),
                                "importance", r.row().importance()
                        )).toList();
                sendJson(ex, 200, Map.of("results", items));

            } else if ("forget".equals(action)) {
                sendError(ex, 403, "Manual memory deletion is disabled. The demo assistant manages context automatically.");

            } else if ("update_importance".equals(action)) {
                sendError(ex, 403, "Manual memory editing is disabled. The demo assistant manages context automatically.");

            } else if ("update".equals(action)) {
                sendError(ex, 403, "Manual memory editing is disabled. The demo assistant manages context automatically.");

            } else if ("context".equals(action)) {
                int maxRecords = JsonUtil.getInt(body, "maxRecords", 20);
                List<MemoryRepository.MemoryRow> ctx = memoryRepo.getByUser(
                        dbId, session.userId(), maxRecords);
                List<Map<String, Object>> items = ctx.stream()
                        .map(m -> Map.<String, Object>of(
                                "id", m.id(), "type", m.memoryType(),
                                "content", m.content() != null ? m.content() : "",
                                "importance", m.importance()
                        )).toList();
                sendJson(ex, 200, Map.of("context", items, "count", items.size()));

            } else {
                sendError(ex, 400, "Unknown memory action: " + action);
            }
        } else if ("GET".equals(method)) {
            int count = memoryRepo.countActive(dbId);
            sendJson(ex, 200, Map.of("count", count));
        } else {
            sendError(ex, 405, "Method not allowed");
        }
    }

    // ─── Vectors ───────────────────────────────────────────────────────

    private void handleVectors(HttpExchange ex) throws IOException {
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        if (!requireMethod(ex, "POST")) return;
        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        String action = JsonUtil.getString(body, "action");
        String namespace = Objects.requireNonNullElse(JsonUtil.getString(body, "namespace"), "default");

        if ("insert".equals(action)) {
            float[] embedding = parseEmbedding(body.get("embedding"));
            if (embedding == null) { sendError(ex, 400, "embedding required"); return; }
            String id = Objects.requireNonNullElse(JsonUtil.getString(body, "id"), UUID.randomUUID().toString());
            vectorRepo.upsert(dbId, namespace, id, embedding,
                    JsonUtil.getString(body, "content"), JsonUtil.getMap(body, "metadata"));
            sendJson(ex, 201, Map.of("id", id));

        } else if ("search".equals(action)) {
            float[] embedding = parseEmbedding(body.get("embedding"));
            if (embedding == null) { sendError(ex, 400, "embedding required"); return; }
            int topK = JsonUtil.getInt(body, "topK", 10);
            String keyword = JsonUtil.getString(body, "keyword");

            List<VectorRepository.VectorRow> results;
            if (keyword != null && !keyword.isEmpty()) {
                results = vectorRepo.hybridSearch(dbId, namespace, embedding, keyword, topK);
            } else {
                results = vectorRepo.search(dbId, namespace, embedding, topK);
            }

            List<Map<String, Object>> items = results.stream()
                    .map(r -> Map.<String, Object>of(
                            "id", r.id(), "score", r.score(),
                            "content", r.content() != null ? r.content() : "",
                            "metadata", r.metadata()
                    )).toList();
            sendJson(ex, 200, Map.of("results", items));

        } else if ("delete".equals(action)) {
            String id = JsonUtil.getString(body, "id");
            boolean deleted = vectorRepo.delete(dbId, namespace, id);
            sendJson(ex, 200, Map.of("deleted", deleted));

        } else {
            sendError(ex, 400, "Unknown vector action: " + action);
        }
    }

    // ─── Semantic ──────────────────────────────────────────────────────

    private void handleSemantic(HttpExchange ex) throws IOException {
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        if (!requireMethod(ex, "POST")) return;
        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        String action = JsonUtil.getString(body, "action");

        if ("add_entity".equals(action)) {
            String id = Objects.requireNonNullElse(JsonUtil.getString(body, "id"),
                    UUID.randomUUID().toString());
            String entityName = JsonUtil.getString(body, "name");
            String entityDesc = JsonUtil.getString(body, "description");
            float[] entityEmb = parseEmbedding(body.get("embedding"));
            if (entityEmb == null && embeddingService != null && entityName != null) {
                String textToEmbed = entityDesc != null ? entityName + " " + entityDesc : entityName;
                entityEmb = embeddingService.embed(textToEmbed);
            }
            GraphRepository.EntityRow entity = new GraphRepository.EntityRow(
                    id, session.userId(),
                    JsonUtil.getString(body, "entityType"),
                    entityName,
                    entityDesc,
                    JsonUtil.getMap(body, "attributes"),
                    entityEmb,
                    JsonUtil.getDouble(body, "confidence", 1.0),
                    JsonUtil.getString(body, "source"),
                    null, null
            );
            graphRepo.upsertEntity(dbId, entity);
            sendJson(ex, 201, Map.of("id", id));

        } else if ("add_relationship".equals(action)) {
            String id = Objects.requireNonNullElse(JsonUtil.getString(body, "id"),
                    UUID.randomUUID().toString());
            GraphRepository.RelationshipRow rel = new GraphRepository.RelationshipRow(
                    id,
                    JsonUtil.getString(body, "sourceEntityId"),
                    JsonUtil.getString(body, "targetEntityId"),
                    JsonUtil.getString(body, "relationshipType"),
                    JsonUtil.getMap(body, "attributes"),
                    JsonUtil.getDouble(body, "weight", 1.0),
                    JsonUtil.getDouble(body, "confidence", 1.0),
                    JsonUtil.getString(body, "source"),
                    null, null, null
            );
            graphRepo.upsertRelationship(dbId, rel);
            sendJson(ex, 201, Map.of("id", id));

        } else if ("find_related".equals(action)) {
            String entityId = JsonUtil.getString(body, "entityId");
            String relType = JsonUtil.getString(body, "relationshipType");
            int maxDepth = JsonUtil.getInt(body, "maxDepth", 2);
            List<GraphRepository.EntityRow> related = graphRepo.findRelated(dbId, entityId, relType, maxDepth);
            List<Map<String, Object>> items = related.stream()
                    .map(e -> Map.<String, Object>of("id", e.id(), "entityType", e.entityType(),
                            "name", e.name() != null ? e.name() : ""))
                    .toList();
            sendJson(ex, 200, Map.of("results", items));

        } else if ("query_entities".equals(action)) {
            String entityType = JsonUtil.getString(body, "entityType");
            List<GraphRepository.EntityRow> entities = graphRepo.findEntitiesByType(dbId, entityType);
            List<Map<String, Object>> items = entities.stream()
                    .map(e -> Map.<String, Object>of("id", e.id(), "entityType", e.entityType(),
                            "name", e.name() != null ? e.name() : "",
                            "description", e.description() != null ? e.description() : ""))
                    .toList();
            sendJson(ex, 200, Map.of("entities", items));

        } else if ("list_entities".equals(action)) {
            int limit = JsonUtil.getInt(body, "limit", 200);
            List<GraphRepository.EntityRow> entities = graphRepo.listEntities(dbId, limit);
            List<Map<String, Object>> items = entities.stream()
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", e.id());
                        m.put("entityType", e.entityType());
                        m.put("name", e.name() != null ? e.name() : "");
                        m.put("description", e.description() != null ? e.description() : "");
                        m.put("confidence", e.confidence());
                        m.put("source", e.source());
                        return m;
                    }).toList();
            sendJson(ex, 200, Map.of("entities", items, "count", items.size()));

        } else if ("list_relationships".equals(action)) {
            int limit = JsonUtil.getInt(body, "limit", 200);
            List<GraphRepository.RelationshipRow> rels = graphRepo.listRelationships(dbId, limit);
            List<Map<String, Object>> items = rels.stream()
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", r.id());
                        m.put("sourceEntityId", r.sourceEntityId());
                        m.put("targetEntityId", r.targetEntityId());
                        m.put("relationshipType", r.relationshipType());
                        m.put("weight", r.weight());
                        m.put("confidence", r.confidence());
                        return m;
                    }).toList();
            sendJson(ex, 200, Map.of("relationships", items, "count", items.size()));

        } else {
            sendError(ex, 400, "Unknown semantic action: " + action);
        }
    }

    // ─── Events ────────────────────────────────────────────────────────

    private void handleEvents(HttpExchange ex) throws IOException {
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        String method = ex.getRequestMethod();
        if ("POST".equals(method)) {
            Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
            String id = UUID.randomUUID().toString();
            EventRepository.EventRow event = new EventRepository.EventRow(
                    id, session.userId(),
                    JsonUtil.getString(body, "eventType"),
                    JsonUtil.getString(body, "action"),
                    JsonUtil.getMap(body, "properties"),
                    JsonUtil.getString(body, "sessionId"),
                    null
            );
            eventRepo.insert(dbId, event);
            sendJson(ex, 201, Map.of("id", id));
        } else if ("GET".equals(method)) {
            int limit = 50;
            String query = ex.getRequestURI().getQuery();
            if (query != null && query.contains("limit=")) {
                limit = Integer.parseInt(query.split("limit=")[1].split("&")[0]);
            }
            List<EventRepository.EventRow> events = eventRepo.queryByUser(dbId, session.userId(), limit);
            List<Map<String, Object>> items = events.stream()
                    .map(e -> Map.<String, Object>of("id", e.id(), "eventType", e.eventType(),
                            "action", e.action() != null ? e.action() : "",
                            "timestamp", e.createdAt().toString()))
                    .toList();
            sendJson(ex, 200, Map.of("events", items));
        } else {
            sendError(ex, 405, "Method not allowed");
        }
    }

    // ─── Webhooks ──────────────────────────────────────────────────────

    private void handleWebhooks(HttpExchange ex) throws IOException {
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        String method = ex.getRequestMethod();
        WebhookRepository webhookRepo = webhookDispatcher.getWebhookRepo();

        if ("GET".equals(method)) {
            List<WebhookRepository.WebhookRow> hooks = webhookRepo.listForDatabase(dbId);
            List<Map<String, Object>> items = hooks.stream()
                    .map(h -> Map.<String, Object>of(
                            "id", h.id(), "url", h.url(),
                            "events", List.of(h.events()),
                            "active", h.active(),
                            "createdAt", h.createdAt().toString()))
                    .toList();
            sendJson(ex, 200, Map.of("webhooks", items));

        } else if ("POST".equals(method)) {
            Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
            String action = JsonUtil.getString(body, "action");

            if ("create".equals(action) || action == null) {
                String url = JsonUtil.getString(body, "url");
                if (url == null || url.isBlank()) {
                    sendError(ex, 400, "url required");
                    return;
                }
                @SuppressWarnings("unchecked")
                List<String> eventsList = (List<String>) body.getOrDefault("events",
                        List.of("context.stored", "context.forgotten"));
                String secret = JsonUtil.getString(body, "secret");
                WebhookRepository.WebhookRow hook = webhookRepo.create(dbId, url,
                        eventsList.toArray(new String[0]), secret);
                sendJson(ex, 201, Map.of("id", hook.id(), "url", hook.url(),
                        "events", List.of(hook.events()), "active", hook.active()));

            } else if ("delete".equals(action)) {
                String id = JsonUtil.getString(body, "id");
                boolean deleted = webhookRepo.delete(dbId, id);
                sendJson(ex, 200, Map.of("deleted", deleted));

            } else if ("toggle".equals(action)) {
                String id = JsonUtil.getString(body, "id");
                boolean active = JsonUtil.getBoolean(body, "active", true);
                boolean updated = webhookRepo.setActive(dbId, id, active);
                sendJson(ex, 200, Map.of("updated", updated, "active", active));

            } else {
                sendError(ex, 400, "Unknown webhook action: " + action);
            }
        } else {
            sendError(ex, 405, "Method not allowed");
        }
    }

    // ─── Bulk Ingest (easy data push) ──────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleBulkIngest(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        SessionRepository.SessionRow session = requireAuth(ex);
        if (session == null) return;
        String dbId = requireDatabaseId(ex, session);
        if (dbId == null) return;

        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        Object itemsObj = body.get("items");
        if (!(itemsObj instanceof List<?> items)) {
            sendError(ex, 400, "items array required");
            return;
        }

        ContextEngine engine = new ContextEngine(dbId, vectorRepo, memoryRepo, graphRepo, eventRepo, embeddingService, webhookDispatcher);
        int success = 0;
        List<String> errors = new ArrayList<>();

        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> entry = new HashMap<>();
            raw.forEach((k, v) -> entry.put(String.valueOf(k), v));
            try {
                engine.store(session.userId(), entry);
                success++;
            } catch (Exception e) {
                errors.add(e.getMessage());
            }
        }

        sendJson(ex, 200, Map.of("ingested", success, "errors", errors, "total", items.size()));
    }

    // External API-key scoped API

    private void handleExternalStore(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        DatabaseApiKeyRepository.ApiKeyAuth auth = requireApiKey(ex);
        if (auth == null) return;
        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        try {
            Map<String, Object> stored = storeExternalItem(auth, body);
            sendJson(ex, 201, stored);
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    private void handleExternalRecall(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        DatabaseApiKeyRepository.ApiKeyAuth auth = requireApiKey(ex);
        if (auth == null) return;
        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        String query = JsonUtil.getString(body, "query");
        if (query == null || query.isBlank()) {
            sendError(ex, 400, "query required");
            return;
        }
        List<RetrievalResult> results = contextRetrievalService.retrieve(
                auth.databaseId(), auth.ownerUserId(), query,
                JsonUtil.getInt(body, "topK", 5), List.of(), true, false);
        sendJson(ex, 200, Map.of(
                "databaseId", auth.databaseId(),
                "results", results.stream().map(this::externalRetrievalToMap).toList()
        ));
    }

    private void handleExternalRetrieve(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        DatabaseApiKeyRepository.ApiKeyAuth auth = requireApiKey(ex);
        if (auth == null) return;
        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        String query = JsonUtil.getString(body, "query");
        if (query == null || query.isBlank()) {
            sendError(ex, 400, "query required");
            return;
        }
        List<RetrievalResult> results = contextRetrievalService.retrieve(
                auth.databaseId(), auth.ownerUserId(), query,
                JsonUtil.getInt(body, "topK", 10),
                sourceTypeFilters(body),
                JsonUtil.getBoolean(body, "includeMemories", true),
                JsonUtil.getBoolean(body, "includeSources", true),
                JsonUtil.getBoolean(body, "includeGraph", true),
                JsonUtil.getBoolean(body, "includeEvents", true));
        sendJson(ex, 200, Map.of(
                "databaseId", auth.databaseId(),
                "results", results.stream().map(this::externalRetrievalToMap).toList()
        ));
    }

    private void handleExternalContextBuild(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        DatabaseApiKeyRepository.ApiKeyAuth auth = requireApiKey(ex);
        if (auth == null) return;
        Map<String, Object> body = JsonUtil.parseBody(ex.getRequestBody());
        try {
            ContextBundleService.BundleBuildResult result = contextBundleService.build(
                    auth.databaseId(), auth.ownerUserId(),
                    requiredBodyString(body, "query"),
                    Objects.requireNonNullElse(JsonUtil.getString(body, "modelProfile"), "medium-context-model"),
                    JsonUtil.getInt(body, "tokenBudget", 4000),
                    Objects.requireNonNullElse(JsonUtil.getString(body, "mode"), "general"),
                    JsonUtil.getBoolean(body, "includeExplanations", true),
                    sourceTypeFilters(body),
                    JsonUtil.getBoolean(body, "includeMemories", true),
                    JsonUtil.getBoolean(body, "includeSources", true),
                    JsonUtil.getBoolean(body, "includeGraph", true),
                    JsonUtil.getBoolean(body, "includeEvents", true)
            );
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("contextBundleId", result.contextBundleId());
            response.put("databaseId", auth.databaseId());
            response.put("query", result.query());
            response.put("modelProfile", result.targetModel());
            response.put("tokenBudget", result.tokenBudget());
            response.put("estimatedTokens", result.estimatedTokens());
            response.put("mode", Objects.requireNonNullElse(JsonUtil.getString(body, "mode"), "general"));
            response.put("items", result.items());
            response.put("formattedContext", result.formattedContext());
            sendJson(ex, 200, response);
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    private Map<String, Object> storeExternalItem(DatabaseApiKeyRepository.ApiKeyAuth auth,
                                                  Map<String, Object> body) {
        String type = requiredBodyString(body, "type").toLowerCase(Locale.ROOT);
        String id;
        switch (type) {
            case "memory" -> {
                String content = requiredBodyString(body, "content");
                Map<String, Object> metadata = JsonUtil.getMap(body, "metadata");
                float[] embedding = embeddingService != null ? embeddingService.embed(content) : null;
                id = "mem_" + UUID.randomUUID();
                memoryRepo.upsert(auth.databaseId(), new MemoryRepository.MemoryRow(
                        id, auth.ownerUserId(), "default",
                        Objects.requireNonNullElse(JsonUtil.getString(body, "memoryType"), "FACT").toUpperCase(Locale.ROOT),
                        content, metadata, embedding,
                        numberFrom(metadata.get("importance"), JsonUtil.getDouble(body, "importance", 0.5)),
                        1.0, 1.0, Objects.requireNonNullElse(JsonUtil.getString(body, "source"), "external-api"),
                        0, Instant.now(), null, null, null, "agent"
                ));
            }
            case "source" -> {
                SourceRepository.SourceRow source = sourceService.create(
                        auth.databaseId(), auth.ownerUserId(),
                        requiredBodyString(body, "sourceType"),
                        requiredBodyString(body, "sourceName"),
                        requiredBodyString(body, "content"),
                        JsonUtil.getMap(body, "metadata"));
                id = source.id();
            }
            case "entity" -> {
                String name = requiredBodyString(body, "name");
                float[] embedding = embeddingService != null ? embeddingService.embed(name + " " + Objects.requireNonNullElse(JsonUtil.getString(body, "description"), "")) : null;
                id = "ent_" + UUID.randomUUID();
                graphRepo.upsertEntity(auth.databaseId(), new GraphRepository.EntityRow(
                        id, auth.ownerUserId(),
                        requiredBodyString(body, "entityType"),
                        name,
                        JsonUtil.getString(body, "description"),
                        JsonUtil.getMap(body, "metadata"),
                        embedding, 1.0, "external-api", null, null
                ));
            }
            case "relationship" -> {
                id = "rel_" + UUID.randomUUID();
                graphRepo.upsertRelationship(auth.databaseId(), new GraphRepository.RelationshipRow(
                        id,
                        requiredBodyString(body, "sourceEntity"),
                        requiredBodyString(body, "targetEntity"),
                        requiredBodyString(body, "relationshipType"),
                        relationshipAttributes(body),
                        1.0, 1.0, "external-api", Instant.now(), null, null
                ));
            }
            case "event" -> {
                id = "evt_" + UUID.randomUUID();
                eventRepo.insert(auth.databaseId(), new EventRepository.EventRow(
                        id, auth.ownerUserId(),
                        Objects.requireNonNullElse(JsonUtil.getString(body, "eventName"), "EXTERNAL_EVENT"),
                        Objects.requireNonNullElse(JsonUtil.getString(body, "description"), JsonUtil.getString(body, "action")),
                        JsonUtil.getMap(body, "metadata"),
                        "external-api",
                        null
                ));
            }
            default -> throw new IllegalArgumentException("Unsupported store type: " + type);
        }
        return Map.of("success", true, "databaseId", auth.databaseId(), "type", type,
                "id", id, "message", "Stored successfully");
    }

    // ─── Static files ──────────────────────────────────────────────────

    private void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path)) path = "/index.html";

        InputStream is = getClass().getResourceAsStream("/static" + path);
        if (is == null) {
            path = "/index.html";
            is = getClass().getResourceAsStream("/static" + path);
        }
        if (is == null) {
            sendError(ex, 404, "Not found");
            return;
        }

        String contentType = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css"
                : path.endsWith(".js") ? "application/javascript" : "application/octet-stream";
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        byte[] content = is.readAllBytes();
        is.close();
        ex.sendResponseHeaders(200, content.length);
        ex.getResponseBody().write(content);
        ex.getResponseBody().close();
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private boolean requireMethod(HttpExchange ex, String method) throws IOException {
        if (ex.getRequestMethod().equalsIgnoreCase(method)) return true;
        sendError(ex, 405, "Method not allowed");
        return false;
    }

    private String requiredBodyString(Map<String, Object> body, String key) {
        String value = JsonUtil.getString(body, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " required");
        return value;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) strings.add(item.toString());
        }
        return strings;
    }

    private List<String> sourceTypeFilters(Map<String, Object> body) {
        Object value = body.get("sourceTypeFilters");
        if (value == null) value = body.get("sourceTypes");
        return stringList(value);
    }

    private Map<String, Object> databaseToMap(DatabaseRepository.DatabaseRow db) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", db.id());
        map.put("name", db.name());
        map.put("description", db.description());
        map.put("status", db.status());
        map.put("createdAt", db.createdAt().toString());
        map.put("updatedAt", db.updatedAt().toString());
        return map;
    }

    private Map<String, Object> apiKeyToMap(DatabaseApiKeyRepository.ApiKeyRow key) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", key.id());
        map.put("databaseId", key.databaseId());
        map.put("keyPrefix", key.keyPrefix());
        map.put("name", key.name());
        map.put("status", key.status());
        map.put("lastUsedAt", key.lastUsedAt() != null ? key.lastUsedAt().toString() : null);
        map.put("createdAt", key.createdAt().toString());
        map.put("revokedAt", key.revokedAt() != null ? key.revokedAt().toString() : null);
        return map;
    }

    private Map<String, Object> sourceToMap(SourceRepository.SourceRow source, boolean includeContent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", source.id());
        map.put("sourceType", source.sourceType());
        map.put("sourceName", source.sourceName());
        map.put("contentHash", source.contentHash());
        map.put("metadata", source.metadata());
        map.put("version", source.version());
        map.put("status", source.status());
        map.put("createdAt", source.createdAt());
        map.put("updatedAt", source.updatedAt());
        if (includeContent) map.put("content", source.content());
        return map;
    }

    private Map<String, Object> codingProjectToMap(CodingProjectRepository.ProjectRow project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.id());
        map.put("databaseId", project.databaseId());
        map.put("name", project.name());
        map.put("description", project.description());
        map.put("sourceType", project.sourceType());
        map.put("githubRepoUrl", project.githubRepoUrl());
        map.put("status", project.status());
        map.put("createdAt", project.createdAt().toString());
        map.put("updatedAt", project.updatedAt().toString());
        return map;
    }

    private Map<String, Object> projectFileToMap(CodingProjectRepository.FileRow file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", file.id());
        map.put("projectId", file.projectId());
        map.put("sourceId", file.sourceId());
        map.put("path", file.path());
        map.put("language", file.language());
        map.put("s3Key", file.s3Key());
        map.put("contentHash", file.contentHash());
        map.put("sizeBytes", file.sizeBytes());
        map.put("version", file.version());
        map.put("status", file.status());
        map.put("createdAt", file.createdAt().toString());
        map.put("updatedAt", file.updatedAt().toString());
        return map;
    }

    private String inferLanguage(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        if (value.endsWith(".java")) return "java";
        if (value.endsWith(".js") || value.endsWith(".jsx")) return "javascript";
        if (value.endsWith(".ts") || value.endsWith(".tsx")) return "typescript";
        if (value.endsWith(".py")) return "python";
        if (value.endsWith(".sql")) return "sql";
        if (value.endsWith(".json")) return "json";
        if (value.endsWith(".yml") || value.endsWith(".yaml")) return "yaml";
        if (value.endsWith(".md")) return "markdown";
        if (value.endsWith(".html")) return "html";
        if (value.endsWith(".css")) return "css";
        if (value.endsWith(".log")) return "log";
        if (value.contains("dockerfile")) return "docker";
        if (value.endsWith(".env") || value.contains("config")) return "config";
        return "text";
    }

    private String sourceTypeForLanguage(String language) {
        String value = language != null ? language.toLowerCase(Locale.ROOT) : "text";
        if (List.of("java", "javascript", "typescript", "python", "sql", "html", "css", "docker").contains(value)) {
            return "code";
        }
        if ("log".equals(value)) return "log";
        if ("markdown".equals(value)) return "markdown";
        if ("json".equals(value) || "yaml".equals(value) || "config".equals(value)) return "config";
        return "document";
    }

    private String contentTypeFor(String language) {
        String value = language != null ? language.toLowerCase(Locale.ROOT) : "text";
        if ("json".equals(value)) return "application/json; charset=utf-8";
        if ("html".equals(value)) return "text/html; charset=utf-8";
        if ("css".equals(value)) return "text/css; charset=utf-8";
        if ("markdown".equals(value)) return "text/markdown; charset=utf-8";
        return "text/plain; charset=utf-8";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value != null ? value : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Map<String, Object> retrievalToMap(RetrievalResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", result.type());
        map.put("id", result.id());
        map.put("sourceId", result.sourceId());
        map.put("chunkId", result.chunkId());
        map.put("memoryId", result.memoryId());
        map.put("sourceName", result.sourceName());
        map.put("sourceType", result.sourceType());
        map.put("content", result.content());
        map.put("tokenEstimate", result.tokenEstimate());
        map.put("vectorScore", result.vectorScore());
        map.put("textScore", result.textScore());
        map.put("freshnessScore", result.freshnessScore());
        map.put("finalScore", result.finalScore());
        map.put("reason", result.reason());
        return map;
    }

    private Map<String, Object> externalRetrievalToMap(RetrievalResult result) {
        Map<String, Object> map = retrievalToMap(result);
        map.put("score", result.finalScore());
        map.put("metadata", result.metadata());
        return map;
    }

    private Map<String, Object> bundleToMap(ContextBundleService.BundleBuildResult bundle) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contextBundleId", bundle.contextBundleId());
        map.put("query", bundle.query());
        map.put("targetModel", bundle.targetModel());
        map.put("tokenBudget", bundle.tokenBudget());
        map.put("estimatedTokens", bundle.estimatedTokens());
        map.put("estimatedCost", bundle.estimatedCost());
        map.put("freshnessStatus", bundle.freshnessStatus());
        map.put("promptCachingSupported", bundle.promptCachingSupported());
        map.put("formattedContext", bundle.formattedContext());
        map.put("items", bundle.items());
        return map;
    }

    private Map<String, Object> bundleItemToMap(ContextBundleRepository.BundleItemRow item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", item.itemType());
        map.put("sourceId", item.sourceId());
        map.put("chunkId", item.chunkId());
        map.put("memoryId", item.memoryId());
        map.put("content", item.content());
        map.put("tokenEstimate", item.tokenEstimate());
        map.put("score", item.score());
        map.put("reason", item.reason());
        map.put("retrievalSource", item.retrievalSource());
        map.put("compressionDecision", item.compressionDecision());
        map.put("freshnessDecision", item.freshnessDecision());
        return map;
    }

    private SessionRepository.SessionRow requireAuth(HttpExchange ex) throws IOException {
        String token = getToken(ex);
        Optional<SessionRepository.SessionRow> session = sessionRepo.validate(token);
        if (session.isPresent()) {
            return session.get();
        }
        Optional<SupabaseJwtValidator.SupabaseUser> supabaseUser = supabaseJwtValidator.validate(token);
        if (supabaseUser.isPresent()) {
            UserRepository.UserRow user = userRepo.upsertExternalUser(
                    supabaseUser.get().id(),
                    supabaseUser.get().email());
            return new SessionRepository.SessionRow(
                    user.id(),
                    user.email(),
                    Instant.now().plus(Duration.ofHours(config.sessionTimeoutHours())));
        }
        sendError(ex, 401, "Authentication required");
        return null;
    }

    private DatabaseApiKeyRepository.ApiKeyAuth requireApiKey(HttpExchange ex) throws IOException {
        String token = getToken(ex);
        Optional<DatabaseApiKeyRepository.ApiKeyAuth> auth = apiKeyRepo.authenticate(token);
        if (auth.isEmpty()) {
            sendError(ex, 401, "Valid database API key required");
            return null;
        }
        return auth.get();
    }

    private String getToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }

    private String requireDatabaseId(HttpExchange ex, SessionRepository.SessionRow session) throws IOException {
        String dbId = ex.getRequestHeaders().getFirst("X-Database-Id");
        if (dbId == null || dbId.isBlank()) {
            sendError(ex, 400, "X-Database-Id header required");
            return null;
        }
        Optional<DatabaseRepository.DatabaseRow> db = databaseRepo.get(dbId, session.userId());
        if (db.isEmpty()) {
            sendError(ex, 404, "Database not found or access denied");
            return null;
        }
        return dbId;
    }

    private double numberFrom(Object value, double defaultValue) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private Map<String, Object> relationshipAttributes(Map<String, Object> body) {
        Map<String, Object> attributes = new LinkedHashMap<>(JsonUtil.getMap(body, "metadata"));
        String description = JsonUtil.getString(body, "description");
        if (description != null && !description.isBlank()) attributes.put("description", description);
        return attributes;
    }

    private void sendJson(HttpExchange ex, int status, Object data) throws IOException {
        String json = JsonUtil.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private void sendError(HttpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, Map.of("error", message));
    }

    @SuppressWarnings("unchecked")
    private float[] parseEmbedding(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List<?> list) {
            float[] arr = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = ((Number) list.get(i)).floatValue();
            }
            return arr;
        }
        return null;
    }

    public UserRepository getUserRepo() { return userRepo; }
    public SessionRepository getSessionRepo() { return sessionRepo; }
    public DatabaseRepository getDatabaseRepo() { return databaseRepo; }
}
