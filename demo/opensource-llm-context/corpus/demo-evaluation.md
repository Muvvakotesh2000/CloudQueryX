# Demo Evaluation Plan

The demo should use the same local open-source LLM twice:

1. Ask the LLM directly with no CloudQueryX context.
2. Ask CloudQueryX to build a context bundle for the same question.
3. Ask the same LLM again with the CloudQueryX formatted context.
4. Compare accuracy, specificity, and groundedness.

Expected result:

The no-context answer may be generic. The CloudQueryX-assisted answer should mention specific architecture details such as Website UI, Java API Server, Context Runtime, Memory Engine, Source Store, Knowledge Graph, Event Store, PostgreSQL, pgvector, full-text search, token budget, and external LLM usage.

This proves CloudQueryX improves answer quality without fine-tuning or training the LLM.
