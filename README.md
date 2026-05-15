<p align="center">
  <img src="https://img.shields.io/badge/CloudQueryX-Context%20Memory%20Engine-6C5CE7?style=for-the-badge&logoColor=white" alt="CloudQueryX"/>
</p>

<h1 align="center">CloudQueryX</h1>

<p align="center">
  <strong>Provider-Neutral Context Memory Engine & Runtime for AI Applications</strong>
</p>

<p align="center">
  <a href="#quick-start"><img src="https://img.shields.io/badge/Quick%20Start-blue?style=flat-square" alt="Quick Start"/></a>
  <a href="#api-reference"><img src="https://img.shields.io/badge/API%20Reference-green?style=flat-square" alt="API Reference"/></a>
  <a href="#deployment"><img src="https://img.shields.io/badge/Deploy-orange?style=flat-square" alt="Deploy"/></a>
  <img src="https://img.shields.io/badge/Java-17+-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17+"/>
  <img src="https://img.shields.io/badge/PostgreSQL-pgvector-336791?style=flat-square&logo=postgresql&logoColor=white" alt="PostgreSQL"/>
  <img src="https://img.shields.io/badge/License-MIT-yellow?style=flat-square" alt="License"/>
  <img src="https://img.shields.io/badge/Docker-Ready-2496ED?style=flat-square&logo=docker&logoColor=white" alt="Docker"/>
</p>

<p align="center">
  One API to store memories, documents, knowledge graphs, and events for any AI application.<br/>
  Multi-signal retrieval with Reciprocal Rank Fusion returns token-budgeted context bundles<br/>
  ready for Claude, GPT, Gemini, or any local model.
</p>

---

## What is CloudQueryX?

CloudQueryX is a **context memory engine** — a dedicated backend service that gives your AI applications persistent, structured memory. Instead of stuffing everything into a single vector store or a prompt, CloudQueryX stores context across **6 specialized data types**, retrieves using **5 signals simultaneously**, and delivers **token-budgeted context bundles** optimized for your target LLM.

**Think of it as a database purpose-built for AI context** — not just vectors, but memories with importance scores, a knowledge graph with entity relationships, document sources with automatic chunking, timeline events, and behavioral data. All queryable through one unified API.

### Who is this for?

- **AI Engineers** building chatbots, agents, copilots, or RAG pipelines who need persistent memory across sessions
- **Product Teams** adding AI features to existing products and needing a context layer
- **Researchers** working with knowledge graphs and multi-signal retrieval

---

## Key Features

### 6 Specialized Data Types

| Type | Description | Use Cases |
|------|-------------|-----------|
| **Memories** | Scored facts with importance, confidence, recency decay | User preferences, decisions, facts, conversation context |
| **Entities** | Knowledge graph nodes with typed attributes | People, projects, tools, concepts, services |
| **Relationships** | Weighted edges between entities | "works_at", "built_with", "brother_of", "depends_on" |
| **Sources** | Documents, code, logs with automatic chunking | Architecture docs, error logs, config files, markdown |
| **Events** | Timeline entries with properties | Deployments, incidents, user actions, milestones |
| **Vectors** | Raw embeddings with namespace isolation | Custom embedding storage, similarity search |

### Multi-Signal Retrieval (5 Signals)

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│   Vector     │  │   BM25      │  │  Knowledge  │  │   Memory    │  │ Behavioral  │
│  Similarity  │  │  Full-Text  │  │   Graph     │  │   Scoring   │  │   Events    │
│  (pgvector)  │  │  (tsvector) │  │ (Traversal) │  │ (Imp+Rec)   │  │ (Freshness) │
└──────┬───────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘
       │                 │                │                 │                │
       └─────────────────┴────────┬───────┴─────────────────┴────────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │   Reciprocal Rank Fusion   │
                    │    (Score Normalization)    │
                    └─────────────┬─────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │   Token Budget Optimizer   │
                    │   (Rank → Compress → Fit)  │
                    └─────────────┬─────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │     Context Bundle         │
                    │  (Ready for any LLM call)  │
                    └───────────────────────────┘
```

### Developer Console

CloudQueryX ships with a full developer console — not a demo UI, but a real tool for managing your context database:

| Section | Description |
|---------|-------------|
| **Overview** | Stats dashboard with memory/entity/source counts, quick actions, recent activity |
| **Playground** | Interactive chat that demonstrates context retrieval + auto-storage in real time |
| **Data Explorer** | Tabbed browser for all 6 data types — search, filter, add, delete |
| **Knowledge Graph** | Canvas-based force-directed visualization of entities and relationships |
| **API Reference** | Interactive playground — pick endpoint, fill params, generate curl/Python/JS, execute live |
| **API Keys** | Create and manage scoped API keys for programmatic access |
| **Webhooks** | Configure event notifications when context changes |
| **Settings** | Database info, health monitoring, system configuration |

### Model Adapters

Built-in context formatting for multiple LLM providers:

- **OpenAI** (GPT-4, GPT-4o, GPT-4.1-mini)
- **Anthropic** (Claude 3.5, Claude 4)
- **Google** (Gemini 1.5, Gemini 2.0)
- **Local Models** (Llama, Mistral via Ollama)
- **Generic** (any model with custom token limits)

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Your AI Application                                  │
│            Chatbot  ·  Agent  ·  Copilot  ·  RAG Pipeline                  │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │  REST API (Bearer token or API key)
┌───────────────────────────────▼─────────────────────────────────────────────┐
│                     CloudQueryX Context Engine                              │
│                                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │  Store    │  │  Recall  │  │ Retrieve │  │  Relate  │  │  Bundle  │    │
│  │ (Ingest) │  │ (Memory) │  │ (Search) │  │ (Graph)  │  │ (Build)  │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Context Runtime                                                     │   │
│  │  Chunking · Embedding · RRF Fusion · Token Budgeting · Formatting   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ Memories │  │ Entities │  │ Sources  │  │  Events  │  │ Vectors  │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘   │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────────────────┐
│                   PostgreSQL + pgvector (Supabase)                          │
│         HNSW indexes · Full-text search · JSONB · Row-level security       │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Quick Start

### Prerequisites

- **Java 17+** — [Download](https://adoptium.net/)
- **PostgreSQL** with **pgvector** extension — [Supabase](https://supabase.com) (free tier works) or local install
- **OpenAI API Key** (optional) — for embeddings and the assistant playground

### 1. Clone and Configure

```bash
git clone https://github.com/Muvvakotesh2000/CloudQueryX.git
cd CloudQueryX

# Copy the example env file and fill in your values
cp .env.example .env
```

Edit `.env` with your database credentials:

```env
# PostgreSQL (Supabase or local)
CLOUDQUERYX_DB_URL=jdbc:postgresql://your-host:5432/postgres
CLOUDQUERYX_DB_USER=postgres
CLOUDQUERYX_DB_PASSWORD=your-password

# Embedding provider: openai, local (ONNX), or none
CLOUDQUERYX_EMBEDDING_PROVIDER=openai
CLOUDQUERYX_OPENAI_API_KEY=sk-your-openai-key

# Assistant LLM (optional — powers the Playground)
OPENAI_API_KEY=sk-your-openai-key
CLOUDQUERYX_LLM_MODEL=gpt-4.1-mini
```

### 2. Initialize the Database

Run the schema against your PostgreSQL database:

```bash
# Using psql
psql -h your-host -U postgres -d postgres -f src/main/resources/schema.sql

# Or in Supabase: paste schema.sql into the SQL Editor
```

### 3. Build and Run

```bash
# Build (skip tests for quick start)
./gradlew build -x test

# Start the web server on port 8080
./gradlew runWeb
```

> **Windows users**: Use `gradlew.bat` instead of `./gradlew`. If your default Java is not 17, set `JAVA_HOME`:
> ```powershell
> $env:JAVA_HOME = "C:\path\to\jdk-17"
> .\gradlew runWeb
> ```

### 4. Open the Console

Navigate to **http://localhost:8080** — sign up, and you're ready to go.

### Quick Start with Docker

```bash
# Build and run with Docker
docker build -t cloudqueryx .
docker run -p 8080:8080 --env-file .env cloudqueryx

# Or use Docker Compose
docker-compose up --build
```

---

## API Reference

CloudQueryX exposes two API layers:

1. **Session API** — Authenticated with `Authorization: Bearer <session-token>` + `X-Database-Id` header
2. **External API (v1)** — Authenticated with `Authorization: Bearer <api-key>` (scoped to a database)

### External API (v1) — For Your Applications

These are the endpoints you integrate into your AI applications:

#### Store Context — `POST /api/v1/store`

Store any type of context data (memories, entities, relationships, sources, events).

```bash
# Store a memory
curl -X POST https://your-instance/api/v1/store \
  -H "Authorization: Bearer cqx_your_api_key" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "memory",
    "memoryType": "FACT",
    "content": "User prefers Python for data processing",
    "importance": 0.9
  }'
```

```python
import requests

# Store a knowledge graph entity
requests.post("https://your-instance/api/v1/store",
    headers={"Authorization": "Bearer cqx_your_api_key"},
    json={
        "type": "entity",
        "entityType": "PERSON",
        "name": "Alice",
        "content": "Senior engineer on the platform team"
    }
)

# Store a relationship
requests.post("https://your-instance/api/v1/store",
    headers={"Authorization": "Bearer cqx_your_api_key"},
    json={
        "type": "relationship",
        "sourceEntity": "Alice",
        "targetEntity": "CloudQueryX",
        "relationshipType": "WORKS_ON"
    }
)
```

```javascript
// Store a source document
const response = await fetch("https://your-instance/api/v1/store", {
  method: "POST",
  headers: {
    "Authorization": "Bearer cqx_your_api_key",
    "Content-Type": "application/json"
  },
  body: JSON.stringify({
    type: "source",
    sourceType: "document",
    name: "Architecture Decision Record",
    content: "We chose PostgreSQL with pgvector for the following reasons..."
  })
});
```

#### Recall Memories — `POST /api/v1/recall`

Recall memories ranked by relevance (combines similarity, importance, recency, and confidence).

```bash
curl -X POST https://your-instance/api/v1/recall \
  -H "Authorization: Bearer cqx_your_api_key" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What programming languages does the user prefer?",
    "limit": 10
  }'
```

#### Retrieve Context — `POST /api/v1/retrieve`

Multi-signal retrieval across all data types using Reciprocal Rank Fusion.

```bash
curl -X POST https://your-instance/api/v1/retrieve \
  -H "Authorization: Bearer cqx_your_api_key" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What is the user working on?",
    "signals": ["vector", "bm25", "graph", "memory", "events"],
    "limit": 20
  }'
```

#### Build Context Bundle — `POST /api/v1/context/build`

Build a token-budgeted context bundle ready to inject into any LLM call.

```bash
curl -X POST https://your-instance/api/v1/context/build \
  -H "Authorization: Bearer cqx_your_api_key" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Help the user debug their deployment issue",
    "tokenBudget": 4000,
    "targetModel": "claude-sonnet-4-6"
  }'
```

**Response:**

```json
{
  "bundleId": "bnd_abc123",
  "estimatedTokens": 3847,
  "formattedContext": "MEMORIES:\n- User prefers Python...\n\nSOURCES:\n- Architecture doc: ...\n\nGRAPH:\n- Alice WORKS_ON CloudQueryX...",
  "items": [
    {"type": "memory", "score": 0.94, "content": "...", "reason": "vector_match"},
    {"type": "source_chunk", "score": 0.87, "content": "...", "reason": "bm25_match"}
  ]
}
```

### Session API — Full Endpoint List

| Method | Endpoint | Description |
|--------|----------|-------------|
| **Auth** | | |
| POST | `/api/auth/signup` | Create account (email, password) |
| POST | `/api/auth/login` | Login, returns session token |
| POST | `/api/auth/logout` | Invalidate session |
| GET | `/api/auth/me` | Get current user info |
| **Databases** | | |
| GET | `/api/databases` | List user's context databases |
| POST | `/api/databases` | Create a new database |
| GET | `/api/databases/{id}/api-keys` | List API keys |
| POST | `/api/databases/{id}/api-keys` | Create API key |
| DELETE | `/api/databases/{id}/api-keys/{keyId}` | Revoke API key |
| **Memory** | | |
| POST | `/api/memory` | Store, recall, forget, update importance |
| GET | `/api/memory` | Get memory count |
| **Vectors** | | |
| POST | `/api/vectors` | Insert, search, delete vectors |
| **Semantic Graph** | | |
| POST | `/api/semantic` | Add/query entities, relationships, traversal |
| **Sources** | | |
| POST | `/api/sources` | Upload documents, code, logs |
| GET | `/api/sources` | List sources |
| **Events** | | |
| POST | `/api/events` | Log events |
| GET | `/api/events` | Query events |
| **Context** | | |
| POST | `/api/context/retrieve` | Multi-signal retrieval |
| POST | `/api/context/build` | Build token-budgeted bundles |
| POST | `/api/context` | Unified context operations |
| **Webhooks** | | |
| GET | `/api/webhooks` | List webhooks |
| POST | `/api/webhooks` | Create, delete, toggle webhooks |
| **Bulk** | | |
| POST | `/api/bulk` | Bulk ingest context items |
| **Assistant** | | |
| POST | `/api/assistant/chat` | Chat with context-aware assistant |
| **System** | | |
| GET | `/api/health` | Health check |

All endpoints (except auth and health) require `Authorization: Bearer <token>` header.
Session API endpoints require `X-Database-Id: <id>` header for database-scoped operations.

---

## Integration Examples

### Python — Build a Context-Aware Chatbot

```python
import requests
from openai import OpenAI

CQX_URL = "https://your-instance"
CQX_KEY = "cqx_your_api_key"
headers = {"Authorization": f"Bearer {CQX_KEY}"}

def chat(user_message: str) -> str:
    # 1. Build a context bundle from CloudQueryX
    bundle = requests.post(f"{CQX_URL}/api/v1/context/build", headers=headers, json={
        "query": user_message,
        "tokenBudget": 4000,
        "targetModel": "gpt-4o"
    }).json()

    # 2. Send to OpenAI with the context
    client = OpenAI()
    response = client.chat.completions.create(
        model="gpt-4o",
        messages=[
            {"role": "system", "content": f"Use this context:\n{bundle['formattedContext']}"},
            {"role": "user", "content": user_message}
        ]
    )

    answer = response.choices[0].message.content

    # 3. Store the conversation as context for next time
    requests.post(f"{CQX_URL}/api/v1/store", headers=headers, json={
        "type": "memory",
        "memoryType": "CONVERSATION",
        "content": f"User asked: {user_message}\nAssistant answered: {answer}",
        "importance": 0.6
    })

    return answer
```

### JavaScript — Add Memory to Your App

```javascript
class ContextMemory {
  constructor(apiUrl, apiKey) {
    this.url = apiUrl;
    this.headers = {
      "Authorization": `Bearer ${apiKey}`,
      "Content-Type": "application/json"
    };
  }

  async store(type, data) {
    const res = await fetch(`${this.url}/api/v1/store`, {
      method: "POST",
      headers: this.headers,
      body: JSON.stringify({ type, ...data })
    });
    return res.json();
  }

  async recall(query, limit = 10) {
    const res = await fetch(`${this.url}/api/v1/recall`, {
      method: "POST",
      headers: this.headers,
      body: JSON.stringify({ query, limit })
    });
    return res.json();
  }

  async buildContext(query, tokenBudget = 4000) {
    const res = await fetch(`${this.url}/api/v1/context/build`, {
      method: "POST",
      headers: this.headers,
      body: JSON.stringify({ query, tokenBudget })
    });
    return res.json();
  }
}

// Usage
const memory = new ContextMemory("https://your-instance", "cqx_your_key");

await memory.store("memory", {
  memoryType: "FACT",
  content: "User is building a React dashboard",
  importance: 0.8
});

const context = await memory.buildContext("What is the user building?");
```

---

## Database Schema

CloudQueryX uses PostgreSQL with pgvector. The schema creates these tables:

```
┌──────────────────────────────────────────────────────────────────┐
│  AUTH & ISOLATION                                                │
│  ├── users              User accounts (email, password_hash)     │
│  ├── sessions           Session tokens with expiration           │
│  ├── databases          Isolated context databases per user      │
│  └── database_api_keys  Scoped API keys (hashed, prefixed)      │
│                                                                  │
│  CONTEXT DATA                                                    │
│  ├── memories           Multi-type memories with embeddings      │
│  │                      (importance, confidence, recency decay)   │
│  ├── entities           Knowledge graph nodes (typed, embedded)   │
│  ├── relationships      Knowledge graph edges (weighted)          │
│  ├── sources            Documents, code, logs                     │
│  ├── context_chunks     Chunked source content                    │
│  ├── context_embeddings Embeddings for chunks                     │
│  ├── events             Timeline events with properties           │
│  └── vectors            Raw embedding vectors                     │
│                                                                  │
│  CONTEXT RUNTIME                                                 │
│  ├── context_bundles    Built context bundles with metadata       │
│  └── context_bundle_items  Individual items in bundles            │
│                                                                  │
│  INTEGRATION                                                     │
│  └── webhooks           Event notification endpoints              │
│                                                                  │
│  SQL ENGINE                                                      │
│  ├── user_tables        User-created tables (schema as JSONB)    │
│  └── user_table_rows    Rows in user tables (data as JSONB)      │
└──────────────────────────────────────────────────────────────────┘
```

**Indexes**: HNSW on embeddings (pgvector cosine), GIN on full-text search (tsvector), B-tree on common query filters.

---

## Deployment

### Docker

```bash
# Single instance
docker build -t cloudqueryx .
docker run -p 8080:8080 --env-file .env cloudqueryx

# Docker Compose (web mode)
docker-compose up --build

# Distributed mode (coordinator + 4 workers)
docker-compose --profile distributed up --build
```

The Docker image uses a multi-stage build (Gradle 8.6 + JDK 17 → Alpine JRE 17) with health checks and tuned JVM options (`-Xms256m -Xmx2g -XX:+UseG1GC`).

### AWS (ECS Fargate)

```bash
./deploy/scripts/deploy-aws.sh production us-east-1
```

The included CloudFormation template provisions:

- **VPC** with public subnets across 2 Availability Zones
- **ECS Fargate** cluster with auto-scaling (1–10 tasks, 70% CPU target)
- **Application Load Balancer** with health checks
- **ECR** repository with image lifecycle policies
- **CloudWatch** log group (30-day retention)
- **IAM** roles for ECS task execution

### CI/CD (GitHub Actions)

| Workflow | Trigger | Actions |
|----------|---------|---------|
| **CI** (`ci.yml`) | Push to `main`, PRs | Build + test (JDK 17/21), Docker build, deploy to ECS |
| **Release** (`release.yml`) | Version tags (`v*`) | Build, test, create GitHub Release, push to GHCR |

---

## Project Structure

```
CloudQueryX/
├── src/main/
│   ├── antlr/                  ANTLR4 SQL grammar
│   ├── proto/                  gRPC / Protobuf service definitions
│   ├── java/com/cloudqueryx/
│   │   ├── config/             Application configuration (env vars)
│   │   ├── web/
│   │   │   ├── api/            HTTP API server, JSON utilities
│   │   │   ├── auth/           Password hashing (PBKDF2), sessions
│   │   │   └── model/          Database isolation, user databases
│   │   ├── context/
│   │   │   └── runtime/        Context retrieval, bundling, chunking,
│   │   │                       token budgeting, model adapters
│   │   ├── repository/         Data access (Memory, Graph, Source,
│   │   │                       Event, Vector, Webhook, Bundle repos)
│   │   ├── embedding/          OpenAI + ONNX local embeddings, caching
│   │   ├── llm/                LLM integration (OpenAI chat service)
│   │   ├── memory/             Memory management and decay
│   │   ├── semantic/           Knowledge graph operations
│   │   ├── vector/             Vector store and similarity search
│   │   ├── webhook/            Webhook dispatcher
│   │   ├── common/             Type system (DataType, Schema, Row)
│   │   ├── expression/         Expression framework (18 types)
│   │   ├── parser/             SQL parser (ANTLR4 visitor)
│   │   ├── optimizer/          Cost-based query optimizer
│   │   ├── planner/            Logical + physical query plans
│   │   ├── execution/          Volcano/iterator execution engine
│   │   ├── distributed/        Coordinator-worker MPP layer (gRPC)
│   │   ├── storage/            Storage abstraction
│   │   ├── benchmark/          Performance testing
│   │   └── cli/                Interactive SQL shell (JLine 3)
│   └── resources/
│       ├── schema.sql           Database schema (PostgreSQL + pgvector)
│       ├── migration-v2.sql     Schema migrations
│       └── static/              Developer console (SPA)
│           ├── index.html       Landing page + dashboard
│           ├── style.css        Sidebar layout + component styles
│           └── app.js           All frontend logic
├── src/test/                    Unit and integration tests
├── deploy/
│   ├── aws/                     CloudFormation template (ECS Fargate)
│   └── scripts/                 Deployment scripts
├── tools/                       Database utilities and test data loaders
├── demo/                        Demo corpus and evaluation scripts
├── .github/workflows/           CI/CD pipelines
├── Dockerfile                   Multi-stage build (Gradle → Alpine JRE)
├── docker-compose.yml           Web + distributed mode profiles
├── build.gradle                 Gradle build (ANTLR + Protobuf plugins)
└── .env.example                 Environment configuration template
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Language** | Java 17 (records, sealed classes, pattern matching) |
| **Database** | PostgreSQL + pgvector (Supabase compatible) |
| **Connection Pool** | HikariCP 5.1.0 |
| **Embeddings** | OpenAI text-embedding-3-small / ONNX Runtime (local) |
| **LLM** | OpenAI GPT-4.1-mini (assistant demo layer) |
| **SQL Parser** | ANTLR4 4.13.1 |
| **RPC** | gRPC 1.62.2 / Protocol Buffers 3.25.3 |
| **HTTP Server** | JDK `com.sun.net.httpserver` (zero dependencies) |
| **JSON** | Jackson 2.17.0 |
| **CLI** | JLine 3.25.1 |
| **Build** | Gradle 8.6 |
| **Testing** | JUnit 5 + AssertJ + Mockito |
| **Containers** | Docker + Docker Compose |
| **CI/CD** | GitHub Actions |
| **Cloud** | AWS ECS Fargate (CloudFormation) |
| **Frontend** | Vanilla JS SPA (no framework, no build step) |

---

## Configuration

All configuration is via environment variables (or `.env` file):

| Variable | Default | Description |
|----------|---------|-------------|
| `CLOUDQUERYX_DB_URL` | — | PostgreSQL JDBC connection URL |
| `CLOUDQUERYX_DB_USER` | — | Database username |
| `CLOUDQUERYX_DB_PASSWORD` | — | Database password |
| `CLOUDQUERYX_PORT` | `8080` | HTTP server port |
| `CLOUDQUERYX_HOST` | `0.0.0.0` | Bind address |
| `CLOUDQUERYX_VECTOR_DIMENSION` | `384` | Embedding dimension |
| `CLOUDQUERYX_DB_POOL_SIZE` | `10` | Connection pool size |
| `CLOUDQUERYX_EMBEDDING_PROVIDER` | `none` | `openai`, `local`, or `none` |
| `CLOUDQUERYX_OPENAI_API_KEY` | — | OpenAI API key (for embeddings) |
| `OPENAI_API_KEY` | — | OpenAI API key (for assistant LLM) |
| `CLOUDQUERYX_LLM_MODEL` | `gpt-4.1-mini` | LLM model for Playground |
| `CLOUDQUERYX_CHAT_TOKEN_BUDGET` | `8000` | Default token budget |
| `CLOUDQUERYX_SESSION_TIMEOUT_HOURS` | `24` | Session expiration |
| `CLOUDQUERYX_DECAY_WORKING` | `0.1` | Memory decay lambda (working) |
| `CLOUDQUERYX_DECAY_SEMANTIC` | `0.001` | Memory decay lambda (semantic) |
| `CLOUDQUERYX_DECAY_EPISODIC` | `0.005` | Memory decay lambda (episodic) |
| `CLOUDQUERYX_DECAY_PROCEDURAL` | `0.002` | Memory decay lambda (procedural) |

---

## SQL Engine

CloudQueryX also includes a full SQL query engine (originally the core project, now complementing the context engine):

```sql
-- Complex analytics
SELECT c.name, SUM(o.amount) as total_spent
FROM customers c
JOIN orders o ON c.id = o.customer_id
WHERE o.amount > 100 AND c.age BETWEEN 25 AND 45
GROUP BY c.name
HAVING SUM(o.amount) > 1000
ORDER BY total_spent DESC
LIMIT 20;
```

### SQL Features

- **Parser**: ANTLR4-based with full SQL dialect (SELECT, JOIN, WHERE, GROUP BY, HAVING, ORDER BY, LIMIT, CTEs, UNION, subqueries)
- **Optimizer**: Cost-based with predicate pushdown, join reordering (System R), projection pruning
- **Execution**: Volcano/iterator model with HashJoin, SortMergeJoin, NestedLoopJoin, HashAggregate
- **Distributed**: Coordinator-worker architecture via gRPC for MPP execution

### CLI

```bash
./gradlew run --console=plain
```

| Command | Description |
|---------|-------------|
| `\tables` | List all tables |
| `\schema <table>` | Show table schema |
| `\explain <sql>` | Show query plan (logical → optimized → physical) |
| `\load <file> <name>` | Load CSV as a table |
| `\benchmark` | Run benchmark suite |
| `\help` | Show help |

---

## Development

### Build

```bash
./gradlew build          # Build + run tests
./gradlew build -x test  # Build without tests
./gradlew runWeb         # Start web server
./gradlew runCli         # Start CLI
```

### Test

```bash
./gradlew test

# View HTML report
open build/reports/tests/test/index.html
```

### Load Test Data

```bash
# Load production-scale test data (500MB, ~20K chunks, 2.5K memories, 960 entities)
java -cp build/libs/cloudqueryx-*.jar tools/LoadCurrentTestDatabase.java
```

---

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes
4. Run tests: `./gradlew test`
5. Submit a pull request

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

<p align="center">
  <strong>CloudQueryX</strong> — Context memory infrastructure for AI applications.
</p>
