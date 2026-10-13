# AI Service (`ai-service`) — Sprint 1

**Team Member:** Himanshu (Lead, `ai-service`)  
**Project:** IT314 DevOps Incident Management System

---

## 1. Role & Sprint 1 Deliverables

| Deliverable | Status | Description |
|---|---|---|
| **`ai-service/` Skeleton** |  Completed | Python + FastAPI service with `GET /health` and `POST /suggest`. |
| **AI Contract Shape** |  Completed | `POST /suggest` returns a fixed example matching the exact contract required by FR-18.1, FR-18.2, and FR-19. |
| **Pytest Suite** |  Completed | 6 automated tests validating status codes, contract shape, and payload validation (422). |
| **CI Workflow** |  Completed | GitHub Actions job (`.github/workflows/ci.yml`) running pytest using the stub (no heavy model in CI). |
| **Ollama Local Setup** |  Completed | Verified local Ollama daemon (`v0.40.3`); selected small model suited for local hardware. |

---

## 2. AI Contract & Requirements Mapping

The response from `POST /suggest` conforms strictly to the project rules:

- **AI only suggests, never automates (DR-10.2 / FR-19.3):** Returns advisory actions only; does not mutate incident status or SLA.
- **Labelled as AI-generated (FR-18.2):** `is_ai_generated: true` explicitly included in root payload.
- **Cites past incidents (FR-18.2):** Each suggestion contains `cited_incident_ids: ["INC-12", ...]` pointing to concrete incidents.
- **Supports Accept / Reject recording (FR-19):** Each suggestion has a stable `id` (e.g., `"sug-001"`) and `status: "pending"` so the frontend and backend can record decisions.
- **Fail-open fallback (FR-18.4 / NFR-12):** When AI service is down or unconfigured (`AI_SERVICE_URL` unset), backend carries on smoothly.

### Contract Schema Example (`POST /suggest`)

```json
{
  "is_ai_generated": true,
  "confidence": 0.87,
  "similar_incidents": [
    {
      "incident_id": "INC-12",
      "title": "Database connection pool timeout on checkout service",
      "similarity_score": 0.89,
      "resolution_summary": "Increased HikariCP max pool size from 20 to 50 and restarted checkout worker pods."
    },
    {
      "incident_id": "INC-44",
      "title": "High latency on payment gateway during traffic surge",
      "similarity_score": 0.82,
      "resolution_summary": "Scaled payment replica pods and restarted stale PostgreSQL idle connections."
    }
  ],
  "suggestions": [
    {
      "id": "sug-001",
      "action": "Check database connection pool metrics (active vs idle) on the target database.",
      "cited_incident_ids": ["INC-12"],
      "reasoning": "Identical symptom seen in INC-12 where HikariCP connection pool was completely exhausted.",
      "status": "pending"
    },
    {
      "id": "sug-002",
      "action": "Restart stale worker processes and scale service replicas to shed immediate connection backpressure.",
      "cited_incident_ids": ["INC-12", "INC-44"],
      "reasoning": "Both INC-12 and INC-44 resolved gateway 504 timeouts by recycling stuck workers while pools recovered.",
      "status": "pending"
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

## 4. Running the Service & Tests Locally

### Quick Start
```powershell
# Install dependencies
pip install -r requirements.txt

# Start FastAPI server
python -m uvicorn main:app --reload --port 8000

# Run all pytest tests
python -m pytest -v
```

### Endpoints
- `GET http://localhost:8000/health` — Health check
- `POST http://localhost:8000/suggest` — AI suggestion endpoint
- `GET http://localhost:8000/docs` — Interactive Swagger documentation UI
