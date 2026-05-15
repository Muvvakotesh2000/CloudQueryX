# PostgreSQL Full-Text Retrieval Notes

PostgreSQL full-text search uses `tsvector` and `tsquery` to index and query natural language text. It is useful when the query contains exact product names, error strings, configuration variables, SQL function names, class names, or endpoint paths.

CloudQueryX should use full-text retrieval together with vector retrieval. Full-text search finds precise terms like `DATABASE_URL`, `HikariCP`, `/api/v1/context/build`, `context_chunks`, `pgvector`, and `VACUUM FULL`. Vector search finds semantically related context even when the exact wording differs.

Production retrieval should:

- Use `plainto_tsquery` or `websearch_to_tsquery` for user queries.
- Keep GIN indexes on generated `search_text` columns.
- Avoid selecting very large text bodies before ranking.
- Rank lightweight IDs first, then fetch limited full content after the top candidates are known.
- Degrade gracefully if source search fails, so memory, graph, and events can still be returned.

This matters for CloudQueryX because source chunks can be large. Retrieving full chunk content for every candidate can create slow requests and database pressure.
