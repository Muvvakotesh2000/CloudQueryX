# pgvector Architecture Notes

pgvector is a PostgreSQL extension that adds a vector data type and similarity operators for embeddings. A context runtime can use it to store embedding vectors beside normal relational metadata, then rank candidate chunks by cosine distance, inner product, or L2 distance.

For CloudQueryX, pgvector is used as one retrieval signal. It should not be the only signal. The better architecture combines vector similarity with full-text search, metadata filters, graph relationships, memory importance, event freshness, and token-budget decisions.

Recommended CloudQueryX pattern:

- Store source documents in a `sources` table.
- Split source text into `context_chunks`.
- Store embeddings in `context_embeddings`.
- Use HNSW indexes for approximate nearest-neighbor vector search.
- Use PostgreSQL full-text search for exact technical terms.
- Merge vector and text results in the context retrieval layer.
- Build a formatted context bundle for the LLM instead of asking the LLM to search raw data.

Important caveat: vector search is good for semantic similarity, but exact terms like configuration keys, error codes, function names, table names, and API paths often need full-text search.
