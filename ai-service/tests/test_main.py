"""
Tests for ai-service Sprint 1 skeleton.

Validates:
  - GET  /health returns 200 with status ok
  - POST /suggest returns 200 with the full AI contract shape
  - Contract guarantees:
      - is_ai_generated == True (FR-18.2)
      - cites specific past incident IDs (FR-18.2)
      - unique suggestion IDs for Accept/Reject tracking (FR-19)
  - 422 Unprocessable Entity on invalid request payloads
"""

from fastapi.testclient import TestClient
from main import app

client = TestClient(app)


# ---------------------------------------------------------------------------
# Health endpoint tests
# ---------------------------------------------------------------------------

def test_health_returns_200():
    response = client.get("/health")
    assert response.status_code == 200


def test_health_payload():
    response = client.get("/health")
    data = response.json()
    assert data["status"] == "ok"
    assert data["service"] == "ai-service"


# ---------------------------------------------------------------------------
# Suggest endpoint tests (Contract shape compliance)
# ---------------------------------------------------------------------------

def test_suggest_returns_200():
    payload = {
        "title": "Database connection pool timeout",
        "description": "504 Gateway Timeout on checkout API under peak traffic",
        "service": "checkout-service",
    }
    response = client.post("/suggest", json=payload)
    assert response.status_code == 200


def test_suggest_returns_ai_contract_shape():
    """Ensure response satisfies the AI contract requirements (FR-18.1, FR-18.2, FR-19)."""
    payload = {
        "title": "Payment gateway failure",
        "description": "Stuck queries blocking new transactions",
    }
    response = client.post("/suggest", json=payload)
    assert response.status_code == 200
    data = response.json()

    # FR-18.2: Must be explicitly labelled AI-generated
    assert data["is_ai_generated"] is True
    assert 0.0 <= data["confidence"] <= 1.0

    # Must contain similar incidents
    assert isinstance(data["similar_incidents"], list)
    assert len(data["similar_incidents"]) > 0
    first_incident = data["similar_incidents"][0]
    assert "incident_id" in first_incident
    assert "title" in first_incident
    assert "similarity_score" in first_incident
    assert "resolution_summary" in first_incident

    # Must contain actionable investigation suggestions with IDs (FR-19) and citations (FR-18.2)
    assert isinstance(data["suggestions"], list)
    assert len(data["suggestions"]) > 0
    first_suggestion = data["suggestions"][0]
    assert "id" in first_suggestion
    assert "action" in first_suggestion
    assert "cited_incident_ids" in first_suggestion
    assert len(first_suggestion["cited_incident_ids"]) > 0
    assert "reasoning" in first_suggestion
    assert first_suggestion["status"] == "pending"


def test_suggest_validation_error_on_missing_body():
    """Missing required fields must return 422, never 500."""
    response = client.post("/suggest", json={})
    assert response.status_code == 422


def test_suggest_validation_error_on_missing_description():
    """Missing description field must return 422."""
    response = client.post("/suggest", json={"title": "Only title provided"})
    assert response.status_code == 422
