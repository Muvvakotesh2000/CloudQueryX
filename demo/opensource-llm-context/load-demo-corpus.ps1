param(
  [string]$CloudQueryXUrl = "http://localhost:9000",
  [Parameter(Mandatory = $true)]
  [string]$ApiKey
)

$ErrorActionPreference = "Stop"
$headers = @{ Authorization = "Bearer $ApiKey" }
$corpusDir = Join-Path $PSScriptRoot "corpus"

function Post-CloudQueryX {
  param([hashtable]$Body)
  Invoke-RestMethod `
    -Uri "$CloudQueryXUrl/api/v1/store" `
    -Method Post `
    -Headers $headers `
    -ContentType "application/json" `
    -Body ($Body | ConvertTo-Json -Depth 10)
}

Write-Host "Loading source corpus from $corpusDir"
Get-ChildItem $corpusDir -Filter *.md | ForEach-Object {
  $content = Get-Content $_.FullName -Raw
  $result = Post-CloudQueryX @{
    type = "source"
    sourceType = "document"
    sourceName = $_.Name
    content = $content
    metadata = @{
      demo = "opensource-llm-context"
      format = "markdown"
      path = $_.Name
    }
  }
  Write-Host "Stored source $($_.Name) -> $($result.id)"
}

$memories = @(
  "CloudQueryX is a provider-neutral Context Runtime and Query Planner for LLM applications.",
  "CloudQueryX prepares context bundles for external LLMs but does not train or call the LLM itself.",
  "CloudQueryX retrieval combines memories, source chunks, graph relationships, events, full-text search, vector search, freshness, and importance.",
  "The proof demo compares the same open-source LLM with and without a CloudQueryX context bundle.",
  "For exact technical terms like DATABASE_URL, HikariCP, API routes, and table names, CloudQueryX should use full-text search in addition to vector search."
)

foreach ($memory in $memories) {
  $result = Post-CloudQueryX @{
    type = "memory"
    memoryType = "FACT"
    content = $memory
    importance = 0.92
    metadata = @{ demo = "opensource-llm-context" }
  }
  Write-Host "Stored memory -> $($result.id)"
}

$entities = @(
  @{ name = "CloudQueryX"; entityType = "PROJECT"; description = "Provider-neutral Context Runtime and Query Planner for LLM applications." },
  @{ name = "Context Runtime"; entityType = "SERVICE"; description = "Retrieves, ranks, compresses, and formats context before an LLM call." },
  @{ name = "pgvector"; entityType = "POSTGRES_EXTENSION"; description = "Stores embedding vectors and supports vector similarity search in PostgreSQL." },
  @{ name = "PostgreSQL Full-Text Search"; entityType = "RETRIEVAL_SIGNAL"; description = "Finds exact technical terms using tsvector and tsquery." },
  @{ name = "Open-Source LLM"; entityType = "MODEL"; description = "Already-trained local model that receives CloudQueryX context and generates the final answer." }
)

foreach ($entity in $entities) {
  $result = Post-CloudQueryX @{
    type = "entity"
    name = $entity.name
    entityType = $entity.entityType
    description = $entity.description
    metadata = @{ demo = "opensource-llm-context" }
  }
  Write-Host "Stored entity $($entity.name) -> $($result.id)"
}

$relationships = @(
  @{ sourceEntity = "CloudQueryX"; relationshipType = "USES"; targetEntity = "pgvector"; description = "CloudQueryX uses pgvector as one vector retrieval signal." },
  @{ sourceEntity = "CloudQueryX"; relationshipType = "USES"; targetEntity = "PostgreSQL Full-Text Search"; description = "CloudQueryX uses full-text search for exact technical matches." },
  @{ sourceEntity = "Context Runtime"; relationshipType = "BUILDS"; targetEntity = "Context Bundle"; description = "The runtime builds token-budgeted context bundles for LLM prompts." },
  @{ sourceEntity = "Open-Source LLM"; relationshipType = "CONSUMES_CONTEXT_FROM"; targetEntity = "CloudQueryX"; description = "The LLM receives formatted context from CloudQueryX before answering." }
)

foreach ($relationship in $relationships) {
  $result = Post-CloudQueryX @{
    type = "relationship"
    sourceEntity = $relationship.sourceEntity
    targetEntity = $relationship.targetEntity
    relationshipType = $relationship.relationshipType
    description = $relationship.description
    metadata = @{ demo = "opensource-llm-context" }
  }
  Write-Host "Stored relationship $($relationship.sourceEntity) $($relationship.relationshipType) $($relationship.targetEntity) -> $($result.id)"
}

$events = @(
  @{ eventName = "DEMO_CORPUS_LOADED"; description = "Open-source LLM context demo corpus was loaded into CloudQueryX." },
  @{ eventName = "CONTEXT_RUNTIME_EVALUATION_STARTED"; description = "Evaluation will compare an open-source LLM with and without CloudQueryX context." }
)

foreach ($event in $events) {
  $result = Post-CloudQueryX @{
    type = "event"
    eventName = $event.eventName
    description = $event.description
    metadata = @{ demo = "opensource-llm-context" }
  }
  Write-Host "Stored event $($event.eventName) -> $($result.id)"
}

Write-Host "Demo corpus loaded."
