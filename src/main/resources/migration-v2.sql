-- CloudQueryX v2 Migration: Conflict Resolution, Memory Scoping, Temporal Queries, Full-Text Search, Webhooks
-- Run this against your Supabase database

-- Database/workspace metadata and database-scoped API keys
ALTER TABLE databases ADD COLUMN IF NOT EXISTS description TEXT DEFAULT '';
ALTER TABLE databases ADD COLUMN IF NOT EXISTS status TEXT DEFAULT 'ACTIVE';
ALTER TABLE databases ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();

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

-- ─── Memory Scoping (#5) ───────────────────────────────────────
ALTER TABLE memories ADD COLUMN IF NOT EXISTS scope TEXT DEFAULT 'user';
CREATE INDEX IF NOT EXISTS idx_memories_scope ON memories (database_id, scope);

-- ─── Full-Text Search (#7) ─────────────────────────────────────
ALTER TABLE memories ADD COLUMN IF NOT EXISTS search_text tsvector;
CREATE INDEX IF NOT EXISTS idx_memories_search_text ON memories USING gin (search_text);

-- Backfill existing rows
UPDATE memories SET search_text = to_tsvector('english', coalesce(content, ''))
WHERE search_text IS NULL;

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

-- ─── Webhooks (#10) ────────────────────────────────────────────
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

-- Context Runtime MVP: sources, chunks, embeddings, and context bundles
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

CREATE TABLE IF NOT EXISTS context_trace_events (
    id TEXT PRIMARY KEY,
    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL,
    bundle_id TEXT,
    stage TEXT NOT NULL,
    payload JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trace_events_request
    ON context_trace_events (database_id, request_id, created_at);
CREATE INDEX IF NOT EXISTS idx_trace_events_bundle
    ON context_trace_events (database_id, bundle_id, created_at);
