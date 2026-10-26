# AI Service (`ai-service`) — Sprint 1

**Team Member:** Himanshu (Lead, `ai-service`)  
**Project:** IT314 DevOps Incident Management System

---

## 1. Role & Sprint 1 Deliverables

| Deliverable | Status | Description |
|---|---|---|
| **`ai-service/` Skeleton** |  Completed | Python + FastAPI service with `GET /health` and `POST /suggest`. |
| **AI Contract Shape** |  Completed | `POST /suggest` matches the exact AI contract shape with citations derived from past incidents (FR-18.1, FR-18.2). |
| **Pytest Suite** |  Completed | 11 automated tests validating status codes, contract shape, dynamic citations, and payload validation (422). |
| **CI Workflow** |  Completed | GitHub Actions job (`.github/workflows/ci.yml`) running pytest using the stub (no heavy model in CI). |
| **Ollama Local Setup** |  Completed | Verified local Ollama daemon (`v0.40.3`); selected small model suited for local hardware. |

---

## 2. AI Contract & Requirements Mapping

The response from `POST /suggest` conforms strictly to the AI contract in `docs/plans/2026-10-17-implementation-plan.md`:

- **AI only suggests, never automates (DR-10.2):** Returns advisory investigation actions only; does not mutate incident status or SLA.
- **Labelled as AI-generated (FR-18.2):** `aiGenerated: true` explicitly included in root payload.
- **Cites past incidents (FR-18.2):** Each suggestion contains `basedOn: [12]` referencing numeric IDs supplied in `pastIncidents`.
- **Backend records decisions:** The AI service only suggests investigation steps; suggestion IDs and status are not returned as the backend records Accept/Reject choices.

### Contract Schema Example (`POST /suggest`)

**Request:**
```json
{
  "incident": {
    "title": "Payment gateway timeout",
    "description": "504 Gateway Timeout on checkout API under peak traffic",
    "service": "Payments",
    "severity": "SEV2"
  },
  "pastIncidents": [
    {
      "id": 12,
      "title": "Database connection pool timeout on checkout service",
      "description": "Increased traffic exhausted database pool workers",
      "fix": "Increased HikariCP max pool size from 20 to 50 and restarted checkout worker pods."
    }
  ]
}
```

**Response (200 OK):**
```json
{
  "aiGenerated": true,
  "suggestions": [
    {
      "step": "Check the payment gateway timeout",
      "basedOn": [12]
    }
  ]
}
```

---

## 3. Ollama Model Selection & Memory Analysis

### System Specs Checked
- **Total RAM:** ~8.0 GB
- **Available Free RAM:** ~1.0 GB

### Model Evaluation

| Model | Parameters | Disk Download | Active RAM Needed | Assessment |
|---|---|---|---|---|
| **`qwen2.5:0.5b`** | 0.5 Billion | ~398 MB | **~0.8 – 1.0 GB** | **(Recommended)** Ultralightweight, fastest inference, fits within ~1 GB free RAM without freezing machine. |
| **`llama3.2:1b`** | 1.0 Billion | ~1.3 GB | **~1.5 – 1.8 GB** | Excellent JSON output capability; requires closing memory-heavy apps on 8 GB systems. |
| **`qwen2.5:1.5b`** | 1.5 Billion | ~986 MB | **~1.8 – 2.0 GB** | Strong reasoning, slightly higher memory overhead. |
| `llama3.2:3b` | 3.0 Billion | ~2.0 GB | ~3.0 – 3.5 GB | Too heavy for 8 GB system (causes swap/page file stutter). |
| `mistral:7b` / `llama3:8b` | 7–8 Billion | ~4.7 GB | ~5.5 – 6.0 GB | Unusable on 8 GB total RAM. |

### How to pull and run the recommended model:
```powershell
# 1. Pull the lightweight model (recommended for 8 GB RAM):
ollama run qwen2.5:0.5b

# Or if you have 2 GB+ free RAM:
ollama run llama3.2:1b
```

---

## 4. Sprint 2: Ollama Embedding Integration (Issue #62)

### Required Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `OLLAMA_URL` | `http://localhost:11434` | Base URL of the Ollama server (`http://ollama:11434` in Docker Compose) |
| `OLLAMA_EMBED_MODEL` | `nomic-embed-text` | Name of the embedding model to use |
| `OLLAMA_TIMEOUT` | `10` | Request timeout in seconds for Ollama HTTP calls |
| `AI_PORT` | `8000` | Port for the AI service |

### Pulling the Embedding Model

The default model is `nomic-embed-text` (~274 MB, 137M parameters, 8192 context length, 768-dimensional embeddings):

```powershell
ollama pull nomic-embed-text
```

### Running Ollama

**Option A: Running locally on host**
```powershell
ollama serve
```

**Option B: Running optionally via Docker Compose**
Ollama is configured under the optional `ollama` profile so developers who do not need Ollama are not forced to run it:
```bash
# Start only the optional Ollama service
docker compose --profile ollama up -d ollama

# Pull the model inside the running Ollama container
docker compose exec ollama ollama pull nomic-embed-text
```

### Calling `POST /embed`

Generate an embedding vector for arbitrary text. `kind` is optional and defaults to `"document"` (`"search_document: "` prefix for stored incidents) or `"query"` (`"search_query: "` prefix for search queries):

```bash
curl -X POST http://localhost:8000/embed \
  -H "Content-Type: application/json" \
  -d '{"text": "Payment gateway timeout on checkout", "kind": "document"}'
```

**Response (200 OK):**
```json
{
  "embedding": [0.0123, -0.0456, 0.0789, ...],
  "model": "nomic-embed-text"
}
```

**Error Handling:**
- `422 Unprocessable Entity`: Missing body, invalid `kind`, or empty/invalid `text`.
- `503 Service Unavailable`: Ollama daemon is unreachable or timed out.
- `502 Bad Gateway`: Ollama returned an error or unexpected response shape.

### Health & Readiness Checks (`GET /health`, `GET /ready`)

- **`GET /health`**: Instant liveness check that does not wait on Ollama. Always returns HTTP 200.
```json
{
  "status": "ok",
  "service": "ai-service",
  "version": "0.2.0"
}
```

- **`GET /ready`**: Readiness check that probes Ollama reachability and verifies whether the configured model is pulled.
```json
{
  "status": "ok",
  "service": "ai-service",
  "version": "0.2.0",
  "ollama": {
    "reachable": true,
    "model_pulled": true,
    "url": "http://localhost:11434",
    "model": "nomic-embed-text"
  }
}
```

---

## 5. Running the Service & Tests Locally

### Quick Start
```powershell
# Install dependencies (runtime + test)
pip install -r requirements-dev.txt

# Start FastAPI server
python -m uvicorn main:app --reload --port 8000

# Run all pytest tests (with mocked Ollama HTTP calls)
python -m pytest -v
```

### Endpoints
- `GET http://localhost:8000/health` — Instant liveness check
- `GET http://localhost:8000/ready` — Readiness & Ollama model check
- `POST http://localhost:8000/suggest` — AI suggestion endpoint (Sprint 1)
- `POST http://localhost:8000/embed` — Text embedding endpoint (Sprint 2)
- `GET http://localhost:8000/docs` — Interactive Swagger OpenAPI documentation

