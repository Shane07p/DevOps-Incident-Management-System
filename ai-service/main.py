"""
ai-service - Sprint 1 (health, suggest) + Sprint 2 (Ollama embeddings) + Sprint 3 (Agent tool loop)
---------------------------------------------------------------------------------------------------
FastAPI service providing AI-assisted incident recommendations and autonomous SRE investigation.

Sprint 1 Scope:
  - GET  /health   -> Instant service liveness check
  - POST /suggest  -> Returns suggestions conforming strictly to the AI contract shape
                      in docs/3-project-management/implementation-plan.md.

Sprint 2 additions (Issue #62):
  - POST /embed    -> Returns a numeric embedding vector via Ollama.
  - GET  /ready    -> Reports whether Ollama is reachable and the model is pulled.

Sprint 3 additions (Issue #91):
  - POST /investigate -> Autonomous AI SRE tool loop on recorded Demo Shop evidence with step,
                         deadline, and token budgets, enforcing real evidence attribution.

Rules enforced by contract:
  - AI only suggests; never mutates incident state or SLA (DR-10.2).
  - Every response is labelled with `aiGenerated: True` (FR-18.2).
  - Suggestions and hypotheses cite specific evidence or past incident IDs.
  - Backend records Accept/Reject; AI service returns suggestions without status or IDs.
  - The app keeps working when Ollama is down or slow (FR-18.4, NFR-12).

Environment variables:
  - OLLAMA_URL          : Base URL of the Ollama server      (default: http://localhost:11434)
  - OLLAMA_EMBED_MODEL  : Embedding model name               (default: nomic-embed-text)
  - OLLAMA_TIMEOUT      : Request timeout in seconds         (default: 10)
  - AGENT_MODEL         : LLM model for agent investigation  (default: qwen2.5:0.5b)
  - AGENT_MAX_STEPS     : Maximum tool-call steps            (default: 5)
  - AGENT_TIMEOUT_SECONDS: Total investigation deadline (s)  (default: 15.0)
  - AGENT_MAX_TOKENS    : Maximum cumulative token budget    (default: 4000)
"""

from __future__ import annotations

from contextlib import asynccontextmanager
import logging
import math
import os
import re
from typing import List, Literal

import httpx
from fastapi import FastAPI, HTTPException, Response, status
from pydantic import BaseModel, Field

from agent_loop import (
    AgentInvestigationLoop,
    AgentLimits,
    EvidenceVault,
    InvestigationRequest,
    InvestigationResult,
    RankedHypothesis,
)
from agent_tools import EvidenceItem, ToolRegistry, build_default_tool_registry
from providers import LlmProvider, OllamaProvider

logger = logging.getLogger("ai-service")


def get_ollama_url() -> str:
    return os.getenv("OLLAMA_URL", "http://localhost:11434").rstrip("/")


def get_ollama_model() -> str:
    return os.getenv("OLLAMA_EMBED_MODEL", "nomic-embed-text")


def get_ollama_timeout() -> float:
    raw = os.getenv("OLLAMA_TIMEOUT", "10")
    try:
        value = float(raw)
        if not math.isfinite(value) or value <= 0:
            return 10.0
        return value
    except (ValueError, TypeError):
        return 10.0


def get_agent_model() -> str:
    return os.getenv("AGENT_MODEL", "qwen2.5:0.5b")


def get_agent_limits() -> AgentLimits:
    """Return validated AgentLimits from environment variables."""
    return AgentLimits.from_env()


def get_agent_max_steps() -> int:
    return get_agent_limits().max_steps


def get_agent_timeout() -> float:
    return get_agent_limits().timeout_seconds


def get_agent_max_tokens() -> int:
    return get_agent_limits().max_tokens


_VERSION = "0.2.0"


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.http_client = httpx.AsyncClient()
    try:
        yield
    finally:
        client = getattr(app.state, "http_client", None)
        if client is not None and not client.is_closed:
            await client.aclose()


app = FastAPI(
    title="Incident AI Service",
    description="Local AI suggestions for DevOps Incident Management System",
    version=_VERSION,
    lifespan=lifespan,
)


async def _get_client() -> httpx.AsyncClient:
    client = getattr(app.state, "http_client", None)
    if client is None or client.is_closed:
        client = httpx.AsyncClient()
        app.state.http_client = client
    return client


# ---------------------------------------------------------------------------
# AI Contract Models (Sprint 1 — unchanged)
# ---------------------------------------------------------------------------

class IncidentDetails(BaseModel):
    """Current incident payload sent by backend."""
    title: str = Field(
        ...,
        min_length=1,
        max_length=200,
        description="Title of the reported incident",
        examples=["Payment gateway timeout on checkout"],
    )
    description: str = Field(
        ...,
        min_length=1,
        max_length=5000,
        description="Incident details and error logs",
        examples=["High response times on /checkout, connection pool exhausted"],
    )
    service: str = Field(
        ...,
        description="Affected service name",
        examples=["Payments"],
    )
    severity: str = Field(
        ...,
        description="Incident severity level",
        examples=["SEV2"],
    )


class PastIncident(BaseModel):
    """Past incident retrieved as context (FR-17, FR-18)."""
    id: int = Field(
        ...,
        description="Numeric identifier of the past incident",
        examples=[12],
    )
    title: str = Field(
        ...,
        min_length=1,
        max_length=200,
        description="Title of the past incident",
        examples=["Database connection pool timeout on checkout service"],
    )
    description: str = Field(
        ...,
        min_length=1,
        max_length=5000,
        description="Details of the past incident",
        examples=["Checkout service returning 504 gateway timeouts under peak load"],
    )
    fix: str = Field(
        ...,
        min_length=1,
        max_length=5000,
        description="Resolution or troubleshooting fix that resolved the past incident",
        examples=["Increased HikariCP connection pool size and restarted pods"],
    )


class SuggestRequest(BaseModel):
    """Request payload adhering to the AI contract shape."""
    incident: IncidentDetails
    pastIncidents: List[PastIncident] = Field(
        default_factory=list,
        description="Relevant past incidents retrieved from database for context",
    )


class Suggestion(BaseModel):
    """Specific actionable suggestion proposed by AI (FR-18.1, FR-18.2)."""
    step: str = Field(
        ...,
        description="Recommended investigation or troubleshooting step",
        examples=["Check the payment gateway timeout"],
    )
    basedOn: List[int] = Field(
        ...,
        description="Past incident IDs this suggestion was derived from",
        examples=[[12]],
    )


class SuggestResponse(BaseModel):
    """Response payload adhering to the exact AI contract shape."""
    aiGenerated: bool = Field(
        True,
        description="Explicit marker that content is AI-generated (FR-18.2)",
    )
    suggestions: List[Suggestion] = Field(
        ...,
        description="Proposed investigation suggestions with citations",
    )


# ---------------------------------------------------------------------------
# Triage Models (Sprint 5 — Issue #247)
# ---------------------------------------------------------------------------

class TriageRequest(BaseModel):
    """Request payload for the /triage endpoint (AI-4, Issue #247)."""
    title: str = Field(
        ...,
        min_length=1,
        max_length=200,
        description="Incident title",
        examples=["Payment gateway timeout on checkout"],
    )
    description: str | None = Field(
        default=None,
        max_length=5000,
        description="Incident description",
        examples=["Checkout requests are failing with gateway timeouts."],
    )
    similar_incidents: list[dict] = Field(
        default_factory=list,
        description="Similar past incidents from AI-1 for context",
    )
    services: list[dict] = Field(
        default_factory=list,
        description="Valid organization services (id and name)",
    )


class TriageServiceHint(BaseModel):
    """Suggested service, nullable when no reliable service can be identified."""
    id: int | None = None
    name: str | None = None


class TriageResponse(BaseModel):
    """Response payload for the /triage endpoint."""
    aiGenerated: bool = Field(True, description="Always True (FR-18.2)")
    severity: str = Field(..., description="Suggested severity: SEV1, SEV2, SEV3, or SEV4")
    service: TriageServiceHint | None = Field(None, description="Suggested service, null when unidentifiable")
    reason: str = Field(..., description="Short, human-readable reason for the suggestion")


# ---------------------------------------------------------------------------
# Embed Models (Sprint 2 — Issue #62)
# ---------------------------------------------------------------------------

class EmbedRequest(BaseModel):
    """Request payload for the /embed endpoint."""
    text: str = Field(
        ...,
        min_length=1,
        max_length=32000,
        description="Text to embed",
        examples=["Payment gateway timeout on checkout"],
    )
    kind: Literal["document", "query"] = Field(
        default="document",
        description="Embedding kind: 'document' for stored incidents, 'query' for search queries",
        examples=["document", "query"],
    )


class EmbedResponse(BaseModel):
    """Response payload for the /embed endpoint."""
    embedding: List[float] = Field(
        ...,
        description="Numeric embedding vector produced by the configured model",
    )
    model: str = Field(
        ...,
        description="Name of the embedding model that produced this vector",
    )


# ---------------------------------------------------------------------------
# Internal helpers — Ollama HTTP interaction
# ---------------------------------------------------------------------------

async def _check_ollama_readiness(url: str, target_model: str, timeout: float = 3.0) -> tuple[bool, bool]:
    """
    Check if Ollama is reachable and whether target_model is pulled.
    Returns (reachable: bool, model_pulled: bool). Never raises.
    """
    try:
        client = await _get_client()
        resp = await client.get(f"{url}/api/tags", timeout=timeout)
        if resp.status_code != 200:
            return False, False
        data = resp.json()
        models = data.get("models", [])
        pulled = any(
            m.get("name") == target_model
            or m.get("name", "").startswith(f"{target_model}:")
            or m.get("model") == target_model
            or m.get("model", "").startswith(f"{target_model}:")
            for m in models
        )
        return True, pulled
    except Exception:
        return False, False


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get("/health")
def health() -> dict:
    """Instant liveness check (Sprint 1 contract). Does not block on Ollama."""
    return {
        "status": "ok",
        "service": "ai-service",
        "version": _VERSION,
    }


@app.get("/ready")
async def ready(response: Response) -> dict:
    """Readiness check that verifies Ollama is reachable and model is pulled."""
    url = get_ollama_url()
    model = get_ollama_model()
    reachable, model_pulled = await _check_ollama_readiness(url, model)
    is_ready = reachable and model_pulled

    if not is_ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE

    return {
        "status": "ok" if is_ready else "not_ready",
        "service": "ai-service",
        "version": _VERSION,
        "ollama": {
            "reachable": reachable,
            "model_pulled": model_pulled,
            "url": url,
            "model": model,
        },
    }


@app.post("/suggest", response_model=SuggestResponse)
def suggest(request: SuggestRequest) -> SuggestResponse:
    """
    Returns recommendations based on incident details and past incident context.
    Conforms to the AI contract in docs/3-project-management/implementation-plan.md.
    In Sprint 1, this returns suggestions referencing IDs from pastIncidents.
    (Real TF-IDF and Ollama LLM integration start in Sprint 2).
    """
    past_ids = [p.id for p in request.pastIncidents]

    if past_ids:
        # Generate suggestions citing IDs provided in pastIncidents
        suggestions = [
            Suggestion(
                step="Check the payment gateway timeout",
                basedOn=[past_ids[0]],
            )
        ]
        if len(past_ids) > 1:
            suggestions.append(
                Suggestion(
                    step="Restart stale worker processes and scale service replicas to shed connection backpressure",
                    basedOn=[past_ids[0], past_ids[1]],
                )
            )
    else:
        # Every suggestion must name its source (FR-18.2); return empty list when no past incidents are provided
        suggestions = []

    return SuggestResponse(
        aiGenerated=True,
        suggestions=suggestions,
    )


# ---------------------------------------------------------------------------
# Triage severity hint endpoint (Sprint 5 — Issue #247)
# ---------------------------------------------------------------------------

# Multi-word phrases checked before single words to ensure specificity (e.g. "slow render" over "slow").
_SEV4_PHRASES = (
    "slow render", "not urgent", "ui issue", "low priority", "background job",
)
_SEV1_PHRASES = (
    "total outage", "0% success", "service unavailable", "all users",
    "fully down", "critical failure", "payment fully", "100% error",
    "complete failure", "data loss",
)
_SEV2_PHRASES = (
    "partial outage", "high error rate", "elevated latency", "most users",
    "checkout fail", "payment fail", "auth fail",
)
_SEV3_PHRASES = (
    "some users", "non-critical", "one region", "few users",
)

# Single-word keywords matched on whole-word boundaries (preventing false positives like dropdown/download/shutdown).
_SEV1_WORDS = (
    "outage", "down", "unavailable", "unreachable",
)
_SEV2_WORDS = (
    "timeout", "degraded", "majority", "slow", "50%", "gateway", "significant", "urgent",
)
_SEV3_WORDS = (
    "intermittent", "flapping", "minority", "occasional", "partial", "sporadic",
)
_SEV4_WORDS = (
    "cosmetic", "typo", "minor",
)


def _matches_phrase(pattern: str, text: str) -> bool:
    """Matches a whole word or boundary-bounded phrase (case-insensitive)."""
    regex = rf'(?<!\w){re.escape(pattern)}(?!\w)'
    return bool(re.search(regex, text, re.IGNORECASE))


def _classify_severity(title: str, description: str | None, similar_incidents: list[dict]) -> tuple[str, str]:
    """
    Rule-based severity classification. Deterministic — no randomness.
    Matches multi-word phrases first, then whole-word single keywords.
    Returns (severity, reason).
    """
    desc_text = (description or "").strip()
    text = (title + (" " + desc_text if desc_text else "")).lower()

    # 1. Multi-word phrases checked first (specific phrases override broad single words)
    # Check SEV4 phrases first so "slow render", "not urgent", "ui issue" override "slow" or "urgent"
    if any(_matches_phrase(p, text) for p in _SEV4_PHRASES):
        return "SEV4", "Keywords indicate a cosmetic or minor issue with negligible user impact."

    if any(_matches_phrase(p, text) for p in _SEV1_PHRASES):
        return "SEV1", "Keywords indicate a total or near-total outage with critical impact on all users."

    if any(_matches_phrase(p, text) for p in _SEV2_PHRASES):
        return "SEV2", "Keywords indicate significant degradation or high-impact partial failure."

    # 2. SEV1 single words (e.g. whole-word "outage", "down", "unavailable", "unreachable")
    if any(_matches_phrase(w, text) for w in _SEV1_WORDS):
        return "SEV1", "Keywords indicate a total or near-total outage with critical impact on all users."

    # 3. SEV2 single words (e.g. whole-word "timeout", "degraded", "slow", "gateway", etc.)
    if any(_matches_phrase(w, text) for w in _SEV2_WORDS):
        return "SEV2", "Keywords indicate significant degradation or high-impact partial failure."

    # 4. Similar incidents evidence (escalate to SEV2 if majority were high-severity)
    severities_from_similar = [
        s.get("severity", "").upper()
        for s in similar_incidents
        if isinstance(s.get("severity"), str)
    ]
    high_severity_count = sum(1 for s in severities_from_similar if s in ("SEV1", "SEV2"))
    if high_severity_count > 0 and len(severities_from_similar) > 0:
        ratio = high_severity_count / len(severities_from_similar)
        if ratio >= 0.5:
            return "SEV2", (
                f"{high_severity_count} of {len(severities_from_similar)} similar past incidents "
                "were high-severity (SEV1 or SEV2), suggesting elevated impact."
            )

    # 5. SEV3 multi-word phrases (e.g. "some users", "non-critical", "one region", "few users")
    if any(_matches_phrase(p, text) for p in _SEV3_PHRASES):
        return "SEV3", "Keywords indicate intermittent or partial impact affecting a minority of users."

    # 6. SEV3 single words (e.g. whole-word "intermittent", "flapping", "sporadic", etc.)
    if any(_matches_phrase(w, text) for w in _SEV3_WORDS):
        return "SEV3", "Keywords indicate intermittent or partial impact affecting a minority of users."

    # 7. SEV4 single words (e.g. whole-word "cosmetic", "typo", "minor")
    if any(_matches_phrase(w, text) for w in _SEV4_WORDS):
        return "SEV4", "Keywords indicate a cosmetic or minor issue with negligible user impact."

    return "SEV3", "No strong severity signal found; defaulting to moderate impact. Review and adjust as needed."


def _extract_service_hint(similar_incidents: list[dict], services: list[dict] = []) -> TriageServiceHint | None:
    """
    Extract the most common service from similar incidents.
    Validates against the provided organization services: returns None if the
    suggested service ID does not belong to the organization's valid services.
    """
    valid_ids: set[int] = set()
    name_to_valid: dict[str, dict] = {}
    for s in services:
        if isinstance(s, dict):
            sid = s.get("id")
            sname = s.get("name")
            if sid is not None and isinstance(sid, int):
                valid_ids.add(sid)
                if sname and isinstance(sname, str):
                    name_to_valid[sname.strip().lower()] = {"id": sid, "name": sname}

    service_counts: dict[str, dict] = {}
    for inc in similar_incidents:
        service = inc.get("service")
        if isinstance(service, dict):
            sid = service.get("id")
            sname = service.get("name")
            if sid is not None and sname:
                key = str(sid)
                service_counts[key] = {"id": sid, "name": sname, "count": service_counts.get(key, {}).get("count", 0) + 1}
        elif isinstance(service, str) and service:
            key = service.strip().lower()
            service_counts[key] = {"id": None, "name": service, "count": service_counts.get(key, {}).get("count", 0) + 1}

    if not service_counts:
        return None

    # Pick the most frequent
    best = max(service_counts.values(), key=lambda x: x["count"])
    best_id = best["id"]
    best_name = best["name"]

    # Validate against organization services if services list was supplied
    if valid_ids or name_to_valid:
        if best_id is not None:
            if best_id not in valid_ids:
                return None
        elif best_name and best_name.strip().lower() in name_to_valid:
            matched = name_to_valid[best_name.strip().lower()]
            best_id = matched["id"]
            best_name = matched["name"]
        else:
            return None

    return TriageServiceHint(id=best_id, name=best_name)


@app.post("/triage", response_model=TriageResponse)
def triage(request: TriageRequest) -> TriageResponse:
    """
    Rule-based triage hint for the incident declare form (AI-4, Issue #247).

    Deterministic: no Ollama call, no randomness. Works when Ollama is offline.
    Uses severity-guide.md keyword sets and evidence from similar past incidents.
    Never mutates incident state (DR-10.2).
    """
    severity, reason = _classify_severity(request.title, request.description, request.similar_incidents)
    service_hint = _extract_service_hint(request.similar_incidents, request.services)
    return TriageResponse(
        aiGenerated=True,
        severity=severity,
        service=service_hint if (service_hint and service_hint.name) else None,
        reason=reason,
    )


@app.post("/embed", response_model=EmbedResponse)
async def embed(request: EmbedRequest) -> EmbedResponse:
    """
    Returns a numeric embedding vector for the supplied text via Ollama.

    Uses the model configured in OLLAMA_EMBED_MODEL (default: nomic-embed-text).
    Prepends prefix according to kind:
      - 'document': "search_document: <text>" (for stored incidents)
      - 'query':    "search_query: <text>"    (for search queries)

    Error responses:
      - 422  if the request body is missing or invalid.
      - 503  if Ollama is unreachable or times out.
      - 502  if Ollama returns an error or unexpected response shape.
    """
    url = get_ollama_url()
    model = get_ollama_model()
    timeout = get_ollama_timeout()

    prefix = "search_query: " if request.kind == "query" else "search_document: "
    payload = {
        "model": model,
        "input": f"{prefix}{request.text}",
    }

    client = await _get_client()
    try:
        resp = await client.post(f"{url}/api/embed", json=payload, timeout=timeout)
    except httpx.TimeoutException:
        logger.warning("Ollama request timed out (url=%s, model=%s)", url, model)
        raise HTTPException(
            status_code=503,
            detail="Ollama request timed out. Make sure Ollama is running and the model is pulled.",
        )
    except (httpx.HTTPError, httpx.InvalidURL) as exc:
        logger.warning("Ollama connection error: %s", exc)
        raise HTTPException(
            status_code=503,
            detail=f"Cannot reach Ollama at {url}. Make sure Ollama is running.",
        ) from exc

    if resp.status_code != 200:
        logger.error("Ollama returned HTTP %s: %s", resp.status_code, resp.text[:200])
        raise HTTPException(
            status_code=502,
            detail=f"Ollama returned an unexpected status: {resp.status_code}",
        )

    try:
        body = resp.json()
        if not isinstance(body, dict):
            raise ValueError("Ollama response is not a JSON object")
        embeddings = body.get("embeddings")
        if not isinstance(embeddings, list) or len(embeddings) == 0:
            raise ValueError("Missing or malformed embeddings field in Ollama response")

        vector = embeddings[0]

        if (
            not isinstance(vector, list)
            or not vector
            or not all(
                type(x) in (int, float) and not isinstance(x, bool) and math.isfinite(x)
                for x in vector
            )
        ):
            raise ValueError(
                "Embedding vector contains non-numeric values, booleans, NaN, or is empty"
            )
    except (ValueError, KeyError, TypeError) as exc:
        logger.error("Unexpected Ollama response shape: %s", exc)
        raise HTTPException(
            status_code=502,
            detail="Ollama returned an unexpected response shape.",
        )

    return EmbedResponse(embedding=vector, model=model)


# ---------------------------------------------------------------------------
# Investigation Endpoint (Sprint 3 — Issue #91)
# ---------------------------------------------------------------------------

def get_investigation_provider() -> LlmProvider:
    """
    Return active model provider for agent investigations. Prefers injected app.state.agent_provider if set (e.g. for deterministic testing),
    otherwise defaults to OllamaProvider using configured AGENT_MODEL and OLLAMA_URL.
    Reuses the FastAPI lifespan-managed http client when available to avoid creating a separate client.
    """
    provider = getattr(app.state, "agent_provider", None)
    if provider is not None:
        return provider
    # Reuse the lifespan-managed HTTP client if present and not closed.
    http_client = getattr(app.state, "http_client", None)
    if http_client is not None and not getattr(http_client, "is_closed", True):
        return OllamaProvider(base_url=get_ollama_url(), model=get_agent_model(), http_client=http_client)
    return OllamaProvider(
        base_url=get_ollama_url(),
        model=get_agent_model(),
    )


@app.post("/investigate", response_model=InvestigationResult)
async def investigate(request: InvestigationRequest) -> InvestigationResult:
    """
    Autonomous AI SRE investigation tool loop on recorded Demo Shop evidence.

    Accepts incident details (service, timestamps, description).
    Resource limits (max steps, timeout, token budget) are enforced exclusively from
    environment variables (AGENT_MAX_STEPS, AGENT_TIMEOUT_SECONDS, AGENT_MAX_TOKENS)
    and cannot be overridden by API callers.

    Executes a bounded multi-turn tool-calling loop:
      - Validates and dispatches registered evidence tools (logs, metrics, deploys, health).
      - Collects structured evidence into an Evidence Vault.
      - Enforces step limits (AGENT_MAX_STEPS), total deadline (AGENT_TIMEOUT_SECONDS),
        and token budget (AGENT_MAX_TOKENS).
      - Strictly validates all model hypotheses against collected evidence.
      - Discards any hypotheses with missing, unknown, or fabricated citations.
      - Returns ranked hypotheses with citations, summary, and execution metrics.
    """
    provider = get_investigation_provider()
    tool_registry = getattr(app.state, "tool_registry", None) or build_default_tool_registry()

    limits = get_agent_limits()

    loop = AgentInvestigationLoop(
        provider=provider,
        tool_registry=tool_registry,
        default_limits=limits,
    )

    result = await loop.run(request=request, limits_override=limits)
    return result
