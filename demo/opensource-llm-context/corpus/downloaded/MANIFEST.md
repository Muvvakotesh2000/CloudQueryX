# Downloaded Open-Source Demo Corpus

These files were downloaded for the CloudQueryX open-source LLM context demo.

The corpus is intentionally focused on the exact proof CloudQueryX needs:

- PostgreSQL as the storage/runtime database
- pgvector as vector retrieval
- PostgreSQL full-text search as exact lexical retrieval
- Ollama as the local open-source LLM API
- llama.cpp as open-source local LLM runtime background

## Files

| File | Source URL | Why it is useful |
| --- | --- | --- |
| `pgvector-readme.md` | `https://raw.githubusercontent.com/pgvector/pgvector/master/README.md` | Vector search, HNSW/IVFFlat, hybrid search, operators, indexing, scaling |
| `pgvector-changelog.md` | `https://raw.githubusercontent.com/pgvector/pgvector/master/CHANGELOG.md` | Versioned pgvector feature history and concrete technical terms |
| `ollama-api.md` | `https://raw.githubusercontent.com/ollama/ollama/main/docs/api.md` | Local open-source LLM API calls for generation and embeddings |
| `llama-cpp-readme.md` | `https://raw.githubusercontent.com/ggml-org/llama.cpp/master/README.md` | Local model runtime and open-source inference background |
| `postgres-textsearch-intro.html` | `https://www.postgresql.org/docs/current/textsearch-intro.html` | PostgreSQL full-text search concepts |
| `postgres-textsearch-tables.html` | `https://www.postgresql.org/docs/current/textsearch-tables.html` | `tsvector` storage and indexing patterns |
| `postgres-textsearch-indexes.html` | `https://www.postgresql.org/docs/current/textsearch-indexes.html` | GIN/GiST text-search index behavior |
| `postgres-index-types.html` | `https://www.postgresql.org/docs/current/indexes-types.html` | PostgreSQL index types for retrieval architecture discussion |

## Demo Questions

```text
Why should CloudQueryX combine pgvector with PostgreSQL full-text search?
```

```text
How should CloudQueryX use HNSW indexes and full-text indexes together?
```

```text
How can an open-source LLM use CloudQueryX context without fine-tuning?
```

```text
What API should a local Ollama model use after CloudQueryX builds formatted context?
```

```text
Why is CloudQueryX more than basic RAG?
```
