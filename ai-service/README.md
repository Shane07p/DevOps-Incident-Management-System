# AI Service (`ai-service`)

What is here so far:

| Part | What it does | Issue |
|---|---|---|
| `GET /health`, `GET /ready` | Liveness; readiness checks that Ollama is reachable and its model is pulled | #21, #62 |
| `POST /suggest` | A stub with the final contract: it returns fixed steps citing the past incidents it is given, with no model call yet | #21 |
| `POST /embed` | An embedding of a text from Ollama (`nomic-embed-text`), for finding similar incidents (AI-1) | #62 |
| `log_patterns.py` | Log-pattern evidence: during an incident, messages that are new, or at least 3 times and twice as often as in the same length of time before it | #92 |
| `agent_loop.py`, `agent_tools.py`, `providers.py` | Autonomous AI SRE tool loop on recorded Demo Shop data with step, time, and token limits, enforcing real evidence attribution | #91 |
| `mcp_server.py` | FastMCP Streamable HTTP MCP server with authenticated read tools (`list_incidents`, `get_incident`, `search_incidents`, `get_service`, `who_is_on_call`, `list_alerts`, etc.) | #162 |
| `data/` | Public postmortems with labels, and a held-out test set for AI-1 | #63 |

The backend does not call this service yet. The planned AI features are in the
[AI/ML roadmap](../docs/2-design/ai-ml-roadmap.md).

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

The response from `POST /suggest` conforms strictly to the AI contract in `docs/3-project-management/implementation-plan.md`:

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
| `AGENT_MODEL` | `qwen2.5:0.5b` | Model used for autonomous AI SRE agent investigations |
| `AGENT_MAX_STEPS` | `5` | Maximum tool steps allowed per investigation |
| `AGENT_TIMEOUT_SECONDS` | `15.0` | Maximum wall-clock deadline in seconds for investigation loop |
| `AGENT_MAX_TOKENS` | `4000` | Maximum cumulative token budget across investigation turns |
| `AI_PORT` | `8000` | Port for the AI service |

### Pulling the Models

1. **Embedding model** (`nomic-embed-text`, ~274 MB, 137M parameters, 8192 context length, 768-dimensional embeddings):
```powershell
ollama pull nomic-embed-text
```

2. **Investigation agent model** (`qwen2.5:0.5b`, ~398 MB, 0.5B parameters, fast tool-calling on 8 GB systems):
```powershell
ollama pull qwen2.5:0.5b
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

# Pull the models inside the running Ollama container
docker compose exec ollama ollama pull nomic-embed-text
docker compose exec ollama ollama pull qwen2.5:0.5b
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
- `POST http://localhost:8000/suggest` — AI suggestion endpoint (a stub with the final contract)
- `POST http://localhost:8000/embed` — Text embedding endpoint (Sprint 2)
- `POST http://localhost:8000/investigate` — Autonomous AI SRE tool loop on recorded Demo Shop evidence (Sprint 3)
- `GET http://localhost:8000/docs` — Interactive Swagger OpenAPI documentation

---

## 6. Sprint 3 AI SRE Investigation Loop (`POST /investigate`, Issue #91)

The AI SRE investigation loop autonomously gathers structured evidence from recorded Demo Shop data, reasons across findings, and produces ranked root-cause hypotheses with verified citations.

### Hard Safety & Resource Limits

| Resource Budget | Environment Variable | Default Value | Validation & Enforcement |
|---|---|---|---|
| **Max Tool Steps** | `AGENT_MAX_STEPS` | `5` | Integer in [1, 50]. Enforced per tool execution; prevents recursive loops from bypassing bounds. |
| **Wall-Clock Deadline** | `AGENT_TIMEOUT_SECONDS` | `15.0` | Finite float in [0.1, 300.0]s. Enforced across the entire investigation loop via monotonic clock. |
| **Model Token Budget** | `AGENT_MAX_TOKENS` | `4000` | Integer in [100, 100000]. Cumulative tracking across input prompt + completion tokens. |

### Evidence Attribution & Grounding
- **Strict Evidence Verification:** Every hypothesis returned MUST cite one or more real `evidenceRefs` (e.g. `ev-log-0`, `ev-metric-0`, `ev-deploy-0`) collected in the investigation.
- **Unsupported Hypothesis Dropping:** Hypotheses with empty citations, fabricated IDs, or unobserved evidence are strictly discarded.
- **Honest Evidence Reporting:** If no evidence is found during investigation, status is `no_evidence` with `hypotheses: []`. If evidence exists but no candidate hypothesis survives verification against the Evidence Vault, status remains `completed` with `hypotheses: []` and an explanation in `summary`.
- **Enforced Safety Bounds:** Resource limits (steps, timeout, tokens) are strictly bound to server configuration environment variables; API callers cannot override limits in the request body.
- **Advisory Role (DR-10.2):** Labelled with `aiGenerated: true`. Never alters incident severity, assignee, status, or SLA targets.

### Example Investigation Call

**Request (`POST /investigate`):**
```json
{
  "service": "payments",
  "incidentStart": "2026-10-27T08:16:07Z",
  "incidentEnd": "2026-10-27T08:16:27Z",
  "description": "504 Gateway Timeouts reported on payment processing."
}
```

**Response (200 OK):**
```json
{
  "aiGenerated": true,
  "status": "completed",
  "summary": "Payments service latency is degraded due to payment gateway slowdown.",
  "hypotheses": [
    {
      "hypothesis": "Upstream payment gateway slowdown exceeding 1.5s SLA",
      "confidence": 0.95,
      "evidenceRefs": ["ev-log-0", "ev-metric-0"],
      "reasoning": "New payment.gateway.slow log events and p99 latency spiked to 2001ms."
    }
  ],
  "evidence": [
    {
      "id": "ev-log-0",
      "sourceTool": "query_log_patterns",
      "evidenceType": "log_pattern",
      "content": "Log pattern 'payment.gateway.slow' on 'payments' is new (0 before) with 5 occurrences during the incident window",
      "details": { "count_before": 0, "count_after": 5, "pattern": "payment.gateway.slow", "status": "new" }
    },
    {
      "id": "ev-metric-0",
      "sourceTool": "query_metric_anomalies",
      "evidenceType": "metric_anomaly",
      "content": "Metric anomaly on 'payments': demo_http_request_duration_seconds - p99 latency 2001.93ms (normal baseline < 50ms, SLA threshold 1500ms)",
      "details": { "metric": "demo_http_request_duration_seconds", "incident_value": 2001.93, "normal_value": 25.0 }
    }
  ],
  "stepsTaken": 2,
  "tokensUsed": 420,
  "durationMs": 142.5
}
```

---

## 7. Sprint 4 MCP Server with Read Tools (Issue #162)

The MCP server connects Claude Desktop, Claude Code, and Cursor to Settle using FastMCP over Streamable HTTP transport:

- **Endpoint:** `http://localhost:8001/mcp` (runs as standalone process to isolate internal AI endpoints).
- **Authentication:** Personal access token (PAT) forwarded strictly via connection headers (`Authorization: Bearer <pat_...>`). Tokens are never tool arguments.
- **Security:**
  - Read-only tools only. No write tools exist in Sprint 4 (Decision D38).
  - The server never accesses the database directly; all requests flow through Settle's backend REST API.
  - Caller tokens are isolated per request context and never leaked into logs, error messages, or tool output.
  - Separate process execution ensures `/suggest`, `/embed`, and `/investigate` remain accessible only from the backend.
- **Read Tools (Active):**
  - `list_incidents`: List all incidents (`GET /api/incidents`).
  - `get_incident`: Incident details with complete timeline (`GET /api/incidents/{id}`).
  - `search_incidents`: Search past incidents by keyword and filters (`GET /api/incidents/search`).
  - `get_service`: Service owner, criticality, and SLA targets (`GET /api/services/{id}`).
  - `who_is_on_call`: Current on-call schedule and fallback (`GET /api/services/{id}/on-call`).
  - `list_alerts`: Alerts inbox (`GET /api/alerts`).
- **Configuration:**
  - `SETTLE_API_URL` (default: `http://localhost:8080`)
  - `SETTLE_API_TIMEOUT` (default: `10`)

For client configuration details, see the [MCP Client Setup Guide](MCP_GUIDE.md).
