"""
ai-service - Sprint 1 Skeleton
-----------------------------------------------------------------------------
FastAPI service providing AI-assisted incident recommendations.

Sprint 1 Scope:
  - GET  /health   -> Service liveness check
  - POST /suggest  -> Fixed mock response matching the exact AI contract shape
                      (compliant with FR-18.1, FR-18.2, FR-19, DR-10.2).

Rules enforced by contract:
  - AI only suggests; never mutates incident state or SLA (DR-10.2)
  - Every suggestion is explicitly labelled `is_ai_generated: True` (FR-18.2)
  - Cites specific past incident IDs used (FR-18.2)
  - Provides suggestion IDs so backend/frontend can record Accept/Reject (FR-19)
"""

from __future__ import annotations

from typing import List, Optional
from fastapi import FastAPI
from pydantic import BaseModel, Field


app = FastAPI(
    title="Incident AI Service",
    description="Local AI suggestions for DevOps Incident Management System",
    version="0.1.0",
)


# ---------------------------------------------------------------------------
# AI Contract Models
# ---------------------------------------------------------------------------

class SuggestRequest(BaseModel):
    """Payload sent by backend when requesting AI recommendations."""
    title: str = Field(..., description="Title of the reported incident", examples=["Checkout database 504 timeouts"])
    description: str = Field(..., description="Details and error logs", examples=["High response times on /checkout, connection pool exhausted"])
    service: Optional[str] = Field(None, description="Affected service name", examples=["payment-service"])


class SimilarIncident(BaseModel):
    """Past incident retrieved as context (FR-17, FR-18)."""
    incident_id: str = Field(..., description="Identifier of the past incident", examples=["INC-12"])
    title: str = Field(..., description="Title of the past incident")
    similarity_score: float = Field(..., description="Cosine similarity score (0.0 - 1.0)", examples=[0.88])
    resolution_summary: str = Field(..., description="How the past incident was resolved")


class InvestigationSuggestion(BaseModel):
    """Specific actionable investigation step proposed by AI (FR-18.1, FR-19)."""
    id: str = Field(..., description="Unique suggestion ID for tracking Accept/Reject decisions (FR-19)")
    action: str = Field(..., description="Recommended investigation or troubleshooting step")
    cited_incident_ids: List[str] = Field(..., description="Incident IDs this recommendation was derived from (FR-18.2)")
    reasoning: str = Field(..., description="Explanation of why this action is suggested")
    status: str = Field(default="pending", description="Accept/Reject state: pending | accepted | rejected")


class SuggestResponse(BaseModel):
    """Response payload adhering to the AI Contract shape."""
    is_ai_generated: bool = Field(True, description="Explicit marker that content is AI-generated (FR-18.2)")
    confidence: float = Field(..., description="Confidence score of suggestions (0.0 - 1.0)")
    similar_incidents: List[SimilarIncident] = Field(..., description="Past incidents matched against input")
    suggestions: List[InvestigationSuggestion] = Field(..., description="Proposed investigation suggestions")


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get("/health")
def health() -> dict[str, str]:
    """Liveness check returning 200 OK so backend knows the service is available."""
    return {
        "status": "ok",
        "service": "ai-service",
        "version": "0.1.0"
    }


@app.post("/suggest", response_model=SuggestResponse)
def suggest(request: SuggestRequest) -> SuggestResponse:
    """
    Returns recommendations based on incident title and description.
    In Sprint 1, this returns a fixed example conforming to the AI contract shape.
    (Real TF-IDF and Ollama LLM integration start in Sprint 2).
    """
    print(f"[ai-service] Received request for incident: '{request.title}' (Service: {request.service})")

    # Fixed example adhering to the AI contract (FR-18.1, FR-18.2, FR-19)
    return SuggestResponse(
        is_ai_generated=True,
        confidence=0.87,
        similar_incidents=[
            SimilarIncident(
                incident_id="INC-12",
                title="Database connection pool timeout on checkout service",
                similarity_score=0.89,
                resolution_summary="Increased HikariCP max pool size from 20 to 50 and restarted checkout worker pods.",
            ),
            SimilarIncident(
                incident_id="INC-44",
                title="High latency on payment gateway during traffic surge",
                similarity_score=0.82,
                resolution_summary="Scaled payment replica pods and restarted stale PostgreSQL idle connections.",
            ),
        ],
        suggestions=[
            InvestigationSuggestion(
                id="sug-001",
                action="Check database connection pool metrics (active vs idle) on the target database.",
                cited_incident_ids=["INC-12"],
                reasoning="Identical symptom seen in INC-12 where HikariCP connection pool was completely exhausted.",
                status="pending",
            ),
            InvestigationSuggestion(
                id="sug-002",
                action="Restart stale worker processes and scale service replicas to shed immediate connection backpressure.",
                cited_incident_ids=["INC-12", "INC-44"],
                reasoning="Both INC-12 and INC-44 resolved gateway 504 timeouts by recycling stuck workers while pools recovered.",
                status="pending",
            ),
        ],
    )
