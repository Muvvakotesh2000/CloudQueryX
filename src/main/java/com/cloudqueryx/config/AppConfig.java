package com.cloudqueryx.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static volatile AppConfig instance;

    private final Dotenv dotenv;

    private AppConfig() {
        this.dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        log.info("Configuration loaded (env vars + .env file)");
    }

    public static AppConfig getInstance() {
        if (instance == null) {
            synchronized (AppConfig.class) {
                if (instance == null) {
                    instance = new AppConfig();
                }
            }
        }
        return instance;
    }

    public String get(String key) {
        return normalize(System.getenv(key), dotenv.get(key));
    }

    public String get(String key, String defaultValue) {
        String value = normalize(System.getenv(key), dotenv.get(key));
        return value != null ? value : defaultValue;
    }

    private String normalize(String primary, String fallback) {
        String value = primary != null && !primary.isBlank() ? primary : fallback;
        if (value == null) return null;
        value = value.trim();
        if (value.length() >= 2) {
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            if (singleQuoted || doubleQuoted) {
                value = value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid int for {}: '{}', using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid long for {}: '{}', using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid double for {}: '{}', using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Boolean.parseBoolean(value.trim());
    }

    // Convenience accessors for commonly used config
    public String dbUrl() { return get("CLOUDQUERYX_DB_URL", "jdbc:postgresql://localhost:5432/cloudqueryx"); }
    public String dbUser() { return get("CLOUDQUERYX_DB_USER", "postgres"); }
    public String dbPassword() { return get("CLOUDQUERYX_DB_PASSWORD", "postgres"); }
    public int dbPoolSize() { return getInt("CLOUDQUERYX_DB_POOL_SIZE", 10); }
    public long dbPoolTimeout() { return getLong("CLOUDQUERYX_DB_POOL_TIMEOUT", 30000); }
    public int serverPort() {
        String cloudQueryXPort = get("CLOUDQUERYX_PORT");
        if (cloudQueryXPort != null && !cloudQueryXPort.isBlank()) {
            return getInt("CLOUDQUERYX_PORT", 8080);
        }
        return getInt("PORT", 8080);
    }
    public String serverHost() { return get("CLOUDQUERYX_HOST", "0.0.0.0"); }
    public int vectorDimension() { return getInt("CLOUDQUERYX_VECTOR_DIMENSION", 384); }
    public int sessionTimeoutHours() { return getInt("CLOUDQUERYX_SESSION_TIMEOUT_HOURS", 24); }
    public double decayWorking() { return getDouble("CLOUDQUERYX_DECAY_WORKING", 0.1); }
    public double decaySemantic() { return getDouble("CLOUDQUERYX_DECAY_SEMANTIC", 0.001); }
    public double decayEpisodic() { return getDouble("CLOUDQUERYX_DECAY_EPISODIC", 0.005); }
    public double decayProcedural() { return getDouble("CLOUDQUERYX_DECAY_PROCEDURAL", 0.002); }

    // Embedding config
    public String embeddingProvider() { return get("CLOUDQUERYX_EMBEDDING_PROVIDER", "local"); }
    public String modelDir() { return get("CLOUDQUERYX_MODEL_DIR", "models/all-MiniLM-L6-v2"); }
    public String openaiApiKey() { return get("CLOUDQUERYX_OPENAI_API_KEY"); }
    public String openaiModel() { return get("CLOUDQUERYX_OPENAI_MODEL", "text-embedding-3-small"); }

    // LLM chat config
    public String llmProvider() { return get("CLOUDQUERYX_LLM_PROVIDER", "openai"); }
    public String llmModel() { return get("CLOUDQUERYX_LLM_MODEL", "gpt-4.1-mini"); }
    public String llmApiKey() {
        String value = get("OPENAI_API_KEY");
        return value != null && !value.isBlank() ? value : get("CLOUDQUERYX_OPENAI_API_KEY");
    }
    public int llmTimeoutSeconds() { return getInt("CLOUDQUERYX_LLM_TIMEOUT_SECONDS", 45); }
    public int chatTokenBudget() { return getInt("CLOUDQUERYX_CHAT_TOKEN_BUDGET", 8000); }

    // Supabase Auth config
    public String supabaseUrl() { return get("SUPABASE_URL"); }
    public String supabaseAnonKey() { return get("SUPABASE_ANON_KEY"); }
    public String supabaseJwtSecret() { return get("SUPABASE_JWT_SECRET"); }

    // Cloud project file storage
    public String awsRegion() { return get("AWS_REGION", "us-east-1"); }
    public String s3Bucket() { return get("AWS_S3_BUCKET"); }
    public boolean s3Enabled() { return s3Bucket() != null && !s3Bucket().isBlank(); }
}
