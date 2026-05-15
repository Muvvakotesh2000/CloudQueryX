# CloudQueryX Open-Source LLM Context Demo

This demo proves CloudQueryX as a context engine for an already-trained open-source LLM.

The claim being tested:

```text
The same local open-source LLM gives better, more grounded answers when CloudQueryX builds the context bundle first.
```

CloudQueryX does not train or fine-tune the model. CloudQueryX stores, retrieves, ranks, and formats context. The LLM generates the final answer.

## Architecture

```text
Demo corpus
  -> CloudQueryX /api/v1/store
  -> memories + sources + chunks + graph + events
  -> CloudQueryX /api/v1/context/build
  -> formattedContext
  -> Ollama open-source LLM
  -> answer
```

## Requirements

- CloudQueryX running locally on port `9000`
- A CloudQueryX database API key
- Ollama installed and running
- A local model, for example `llama3.1:8b`

Install Ollama from:

```text
https://ollama.com
```

Pull a model:

```powershell
ollama pull llama3.1:8b
```

## Step 1: Start CloudQueryX

From the project root:

```powershell
$env:JAVA_HOME = "C:\Java\jdk-17.0.11"
.\gradlew.bat run --args="web 9000"
```

Open:

```text
http://localhost:9000
```

Log in, create/select a context database, and generate a database API key.

## Step 2: Load Demo Data

From the project root:

```powershell
.\demo\opensource-llm-context\load-demo-corpus.ps1 `
  -CloudQueryXUrl "http://localhost:9000" `
  -ApiKey "cqx_live_your_database_key_here"
```

This stores:

- source documents
- memories
- entities
- relationships
- events

## Step 3: Test Retrieval In Website

Try this in the CloudQueryX Retrieve tab:

```text
Why should CloudQueryX combine pgvector with PostgreSQL full-text search?
```

Expected context types:

- source chunks about pgvector
- source chunks about PostgreSQL full-text search
- memory explaining hybrid retrieval
- graph relationship: CloudQueryX USES pgvector
- graph relationship: CloudQueryX USES PostgreSQL Full-Text Search

## Step 4: Run LLM Comparison

Make sure Ollama is running:

```powershell
ollama serve
```

In another terminal:

```powershell
.\demo\opensource-llm-context\compare-with-ollama.ps1 `
  -CloudQueryXUrl "http://localhost:9000" `
  -OllamaUrl "http://localhost:11434" `
  -Model "llama3.1:8b" `
  -ApiKey "cqx_live_your_database_key_here" `
  -Question "Explain why CloudQueryX should combine pgvector with PostgreSQL full-text search when building context for an open-source LLM."
```

The script writes a Markdown comparison file under:

```text
demo/opensource-llm-context/runs/
```

## Good Test Questions

```text
Explain why CloudQueryX should combine pgvector with PostgreSQL full-text search.
```

```text
What is the difference between basic RAG and a context runtime?
```

```text
What does CloudQueryX do before an external LLM answers?
```

```text
Why should CloudQueryX rank lightweight IDs before fetching full source chunk content?
```

```text
How can we prove CloudQueryX improves an open-source LLM without fine-tuning it?
```

## How To Judge The Result

The answer with CloudQueryX context should be more specific. It should mention concrete details from the corpus:

- pgvector
- HNSW
- full-text search
- `tsvector`
- exact technical terms
- memories
- source chunks
- graph relationships
- events
- token-budgeted context bundles
- external LLM usage

The answer without context may still sound fluent, but it is likely to be more generic.

## Important

Do not run 500MB or 1GB demo data on a tiny Supabase free database. For large loads, use local PostgreSQL + pgvector or Oracle Cloud Always Free with a larger disk.
