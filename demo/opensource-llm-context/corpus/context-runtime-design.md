# Context Runtime Design

CloudQueryX is a provider-neutral Context Runtime and Query Planner for LLM applications. Its job is to prepare relevant context before an LLM call.

CloudQueryX does:

- Store long-term memories.
- Store source documents and code snippets.
- Chunk source text.
- Retrieve relevant memories and source chunks.
- Include knowledge graph entities and relationships.
- Include event history and freshness signals.
- Rank context using multiple signals.
- Optimize selected context for a token budget.
- Format the context bundle for an external LLM.

CloudQueryX does not:

- Train the LLM.
- Fine-tune model weights.
- Call a specific provider by default.
- Replace an LLM.

The best proof demo compares the same open-source LLM with and without CloudQueryX context. If the model answers better when given CloudQueryX's formatted context bundle, the context engine is working.
