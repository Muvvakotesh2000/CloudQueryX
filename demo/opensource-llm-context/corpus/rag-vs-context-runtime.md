# RAG Versus Context Runtime

Basic RAG usually means embedding documents, retrieving top chunks, and placing those chunks into a prompt. A Context Runtime is broader.

A Context Runtime can plan what to retrieve, combine multiple retrieval systems, explain why items were selected, apply freshness and importance, include graph relationships, compress or trim content, and build a model-ready context bundle.

CloudQueryX should be evaluated as a context runtime, not only as a vector database.

Useful evaluation questions:

- Does the system retrieve exact configuration details?
- Does it retrieve semantic context when the query uses different wording?
- Does it include graph relationships when architecture relationships matter?
- Does it include events when recent decisions matter?
- Does the final context fit within the requested token budget?
- Does the LLM answer improve when using the context bundle?
