param(
  [string]$CloudQueryXUrl = "http://localhost:9000",
  [string]$OllamaUrl = "http://localhost:11434",
  [string]$Model = "llama3.1:8b",
  [Parameter(Mandatory = $true)]
  [string]$ApiKey,
  [string]$Question = "Explain why CloudQueryX should combine pgvector with PostgreSQL full-text search when building context for an open-source LLM."
)

$ErrorActionPreference = "Stop"
$headers = @{ Authorization = "Bearer $ApiKey" }

function Invoke-Ollama {
  param([string]$Prompt)
  $body = @{
    model = $Model
    prompt = $Prompt
    stream = $false
    options = @{
      temperature = 0.2
      num_predict = 500
    }
  } | ConvertTo-Json -Depth 8

  $response = Invoke-RestMethod `
    -Uri "$OllamaUrl/api/generate" `
    -Method Post `
    -ContentType "application/json" `
    -Body $body
  return $response.response
}

Write-Host "Question:"
Write-Host $Question
Write-Host ""

$directPrompt = @"
Answer the question accurately and concisely.

Question:
$Question
"@

Write-Host "Running open-source LLM without CloudQueryX context..."
$withoutContext = Invoke-Ollama $directPrompt

$bundleRequest = @{
  query = $Question
  modelProfile = "medium-context-model"
  tokenBudget = 4000
  mode = "research"
  includeExplanations = $true
  includeMemories = $true
  includeSources = $true
  includeGraph = $true
  includeEvents = $true
} | ConvertTo-Json -Depth 8

Write-Host "Building CloudQueryX context bundle..."
$bundle = Invoke-RestMethod `
  -Uri "$CloudQueryXUrl/api/v1/context/build" `
  -Method Post `
  -Headers $headers `
  -ContentType "application/json" `
  -Body $bundleRequest

$contextPrompt = @"
Use the following CloudQueryX context when answering. If the context does not contain the answer, say what is missing.

$($bundle.formattedContext)

Question:
$Question
"@

Write-Host "Running same open-source LLM with CloudQueryX context..."
$withContext = Invoke-Ollama $contextPrompt

$outDir = Join-Path $PSScriptRoot "runs"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outFile = Join-Path $outDir "comparison-$stamp.md"

@"
# CloudQueryX Open-Source LLM Comparison

## Question

$Question

## LLM Without CloudQueryX Context

$withoutContext

## CloudQueryX Bundle

- Bundle ID: $($bundle.contextBundleId)
- Estimated Tokens: $($bundle.estimatedTokens)
- Items: $($bundle.items.Count)

## LLM With CloudQueryX Context

$withContext

## Context Items

$($bundle.items | ConvertTo-Json -Depth 8)
"@ | Set-Content -Path $outFile -Encoding UTF8

Write-Host "Comparison written to $outFile"
