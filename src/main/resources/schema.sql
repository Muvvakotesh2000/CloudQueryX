-- CloudQueryX AI Context Engine - PostgreSQL Schema
-- Requires: PostgreSQL 15+ with pgvector extension
-- Run this against your Supabase database

CREATE EXTENSION IF NOT EXISTS vector;

-- ─── Auth ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sessions (
    token TEXT PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions (expires_at);

-- ─── User Databases ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS databases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT DEFAULT '',
    status TEXT DEFAULT 'ACTIVE',
    updated_at TIMESTAMPTZ DEFAULT now(),
    created_at TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE databases ADD COLUMN IF NOT EXISTS description TEXT DEFAULT '';
ALTER TABLE databases ADD COLUMN IF NOT EXISTS status TEXT DEFAULT 'ACTIVE';
ALTER TABLE databases ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_databases_user ON databases (user_id);

CREATE TABLE IF NOT EXISTS database_api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
    owner_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    key_hash TEXT NOT NULL UNIQUE,
    key_prefix TEXT NOT NULL,
    name TEXT NOT NULL,
    status TEXT DEFAULT 'ACTIVE',
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_database_api_keys_database ON database_api_keys (database_id);
CREATE INDEX IF NOT EXISTS idx_database_api_keys_owner ON database_api_keys (owner_user_id);
CREATE INDEX IF NOT EXISTS idx_database_api_keys_prefix ON database_api_keys (key_prefix);

-- ─── Vectors ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS vectors (
    id TEXT NOT NULL,
    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
    namespace TEXT DEFAULT 'default',
    embedding vector(384),
    content TEXT,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (database_id, namespace, id)
);

CREATE INDEX IF NOT EXISTS idx_vectors_embedding ON vectors
    USING hnsw (embedding vector_cosine_ops);

-- ─── Memories ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS memories (
    id TEXT NOT NULL,
    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    namespace TEXT DEFAULT 'default',
    memory_type TEXT NOT NULL,
    content TEXT,
    metadata JSONB DEFAULT '{}',
    embedding vector(384),
    importance DOUBLE PRECISION DEFAULT 0.5,
    confidence DOUBLE PRECISION DEFAULT 1.0,
    recency DOUBLE PRECISION DEFAULT 1.0,
    source TEXT,
    access_count INT DEFAULT 0,
    valid_from TIMESTAMPTZ DEFAULT now(),
    valid_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    expired_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (database_id, id)
);

-- Scope: 'user' (permanent), 'session' (auto-expires), 'agent' (shared across agents)
ALTER TABLE memories ADD COLUMN IF NOT EXISTS scope TEXT DEFAULT 'user';

-- Full-text search vector (auto-populated via trigger)
ALTER TABLE memories ADD COLUMN IF NOT EXISTS search_text tsvector;

CREATE INDEX IF NOT EXISTS idx_memories_embedding ON memories
    USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_memories_type ON memories (database_id, memory_type);
CREATE INDEX IF NOT EXISTS idx_memories_user ON memories (database_id, user_id);
CREATE INDEX IF NOT EXISTS idx_memories_valid ON memories (database_id, valid_until);
CREATE INDEX IF NOT EXISTS idx_memories_scope ON memories (database_id, scope);
CREATE INDEX IF NOT EXISTS idx_memories_search_text ON memories USING gin (search_text);

-- Auto-update tsvector on insert/update
CREATE OR REPLACE FUNCTION memories_search_text_trigger() RETURNS trigger AS $$
BEGIN
    NEW.search_text := to_tsvector('english', coalesce(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_memories_search_text ON memories;
CREATE TRIGGER trg_memories_search_text
    BEFORE INSERT OR UPDATE OF content ON memories
    FOR EACH ROW EXECUTE FUNCTION memories_search_text_trigger();

-- ─── Knowledge Graph: Entities ──────────────────────────────────
CREATE TABLE IF NOT EXISTS entities (
    id TEXT NOT NULL,
    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
    user_id TEXT,
    entity_type TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    attributes JSONB DEFAULT '{}',
    embedding vector(384),
    confidence DOUBLE PRECISION DEFAULT 1.0,
    source TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (database_id, id)
);

CREATE INDEX IF NOT EXISTS idx_entities_type ON entities (database_id, entity_type);
CREATE INDEX IF NOT EXISTS idx_entities_embedding ON entities
    USING hnsw (embedding vector_cosine_ops);

-- ─── Knowledge Graph: Relationships ─────────────────────────────
CREATE TABLE IF NOT EXISTS relationships (
    id TEXT NOT NULL,
    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
    source_entity_id TEXT NOT NULL,
    target_entity_id TEXT NOT NULL,
    relationship_type TEXT NOT NULL,
    attributes JSONB DEFAULT '{}',
    weight DOUBLE PRECISION DEFAULT 1.0,
    confidence DOUBLE PRECISION DEFAULT 1.0,
    source TEXT,
    valid_from TIMESTAMPTZ DEFAULT now(),
    valid_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (database_id, id)
);

CREATE INDEX IF NOT EXISTS idx_rel_source ON relationships (database_id, source_entity_id);
CREATE INDEX IF NOT EXISTS idx_rel_target ON relationships (database_id, target_entity_id);
CREATE INDEX IF NOT EXISTS idx_rel_type ON relationships (database_id, relationship_type);

-- ─── Events ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS events (
    id TEXT NOT NULL,
    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    action TEXT,
    properties JSONB DEFAULT '{}',
    session_id TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (database_id, id)
);

CREATE INDEX IF NOT EXISTS idx_events_user ON events (database_id, user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_type ON events (database_id, event_type);

-- ─── User Tables (SQL Engine) ───────────────────────────────────
CREATE TABLE IF NOT EXISTS user_tables (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    schema_def JSONB NOT NULL,
    row_count INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE (database_id, name)
);

CREATE TABLE IF NOT EXISTS user_table_rows (
    id BIGSERIAL PRIMARY KEY,
    table_id UUID REFERENCES user_tables(id) ON DELETE CASCADE,
    data JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_rows_table ON user_table_rows (table_id);

-- ─── Webhooks ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS webhooks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    events TEXT[] NOT NULL DEFAULT '{}',
    secret TEXT,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_webhooks_db ON webhooks (database_id, active);

-- Context Runtime: Sources
CREATE TABLE IF NOT EXISTS sources (
    id TEXT NOT NULL,
    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    source_type TEXT NOT NULL,
    source_name TEXT NOT NULL,
    content TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    metadata JSONB DEFAULT '{}',
    version INT DEFAULT 1,
    status TEXT DEFAULT 'ACTIVE',
    search_text tsvector,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (database_id, id)
);

CREATE INDEX IF NOT EXISTS idx_sources_db_type ON sources (database_id, source_type);
CREATE INDEX IF NOT EXISTS idx_sources_db_created ON sources (database_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sources_status ON sources (database_id, status);
CREATE INDEX IF NOT EXISTS idx_sources_search_text ON sources USING gin (search_text);

CREATE OR REPLACE FUNCTION sources_search_text_trigger() RETURNS trigger AS $$
BEGIN
    NEW.search_text := to_tsvector('english', coalesce(NEW.source_name, '') || ' ' || coalesce(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sources_search_text ON sources;
CREATE TRIGGER trg_sources_search_text
    BEFORE INSERT OR UPDATE OF source_name, content ON sources
    FOR EACH ROW EXECUTE FUNCTION sources_search_text_trigger();

CREATE TABLE IF NOT EXISTS context_chunks (
    id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    database_id UUID NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    token_estimate INT DEFAULT 0,
    content_hash TEXT NOT NULL,
    metadata JSONB DEFAULT '{}',
    search_text tsvector,
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (database_id, id),
    FOREIGN KEY (database_id, source_id) REFERENCES sources(database_id, id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_chunks_source ON context_chunks (database_id, source_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_chunks_created ON context_chunks (database_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chunks_search_text ON context_chunks USING gin (search_text);

CREATE OR REPLACE FUNCTION chunks_search_text_trigger() RETURNS trigger AS $$
BEGIN
    NEW.search_text := to_tsvector('english', coalesce(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_chunks_search_text ON context_chunks;
CREATE TRIGGER trg_chunks_search_text
    BEFORE INSERT OR UPDATE OF content ON context_chunks
    FOR EACH ROW EXECUTE FUNCTION chunks_search_text_trigger();

CREATE TABLE IF NOT EXISTS context_embeddings (
    id TEXT NOT NULL,
    database_id UUID NOT NULL,
    chunk_id TEXT NOT NULL,
    embedding vector(384),
    embedding_model TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (database_id, id),
    FOREIGN KEY (database_id, chunk_id) REFERENCES context_chunks(database_id, id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_context_embeddings_chunk ON context_embeddings (database_id, chunk_id);
CREATE INDEX IF NOT EXISTS idx_context_embeddings_embedding ON context_embeddings
    USING hnsw (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS context_bundles (
    id TEXT NOT NULL,
    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    query TEXT NOT NULL,
    target_model TEXT NOT NULL,
    mode TEXT DEFAULT 'general',
    token_budget INT NOT NULL,
    estimated_tokens INT DEFAULT 0,
    freshness_status TEXT DEFAULT 'VALID',
    formatted_context TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (database_id, id)
);

CREATE INDEX IF NOT EXISTS idx_context_bundles_db_created ON context_bundles (database_id, created_at DESC);

CREATE TABLE IF NOT EXISTS context_bundle_items (
    id TEXT NOT NULL,
    database_id UUID NOT NULL,
    bundle_id TEXT NOT NULL,
    item_type TEXT NOT NULL,
    source_id TEXT,
    chunk_id TEXT,
    memory_id TEXT,
    content TEXT NOT NULL,
    token_estimate INT DEFAULT 0,
    score DOUBLE PRECISION DEFAULT 0,
    reason TEXT,
    retrieval_source TEXT,
    compression_decision TEXT DEFAULT 'NONE',
    freshness_decision TEXT DEFAULT 'VALID',
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (database_id, id),
    FOREIGN KEY (database_id, bundle_id) REFERENCES context_bundles(database_id, id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_bundle_items_bundle ON context_bundle_items (database_id, bundle_id);
