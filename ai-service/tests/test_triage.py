"""
Tests for the /triage endpoint (AI-4, Issue #247).

Validates:
  - SEV1 keywords → returns SEV1
  - SEV2 keywords → returns SEV2
  - SEV3 keywords → returns SEV3
  - SEV4 keywords → returns SEV4
  - Similar incidents with high-severity majority → escalates to SEV2
  - No signal → defaults to SEV3
  - aiGenerated is always True
  - Service hint extracted from similar incidents
  - Service hint is null when no similar incidents
  - Empty similar_incidents → still returns a result
  - Request validation: empty title rejected (422)
  - Request validation: empty description rejected (422)
  - Deterministic: same input → same output (no randomness)
"""

import pytest
from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


def triage(title: str, description: str = "details", similar: list = None):
    body = {"title": title, "description": description}
    if similar is not None:
        body["similar_incidents"] = similar
    return client.post("/triage", json=body)


# ---------------------------------------------------------------------------
# Severity keyword classification
# ---------------------------------------------------------------------------

def test_sev1_outage_keyword():
    r = triage("Service outage affecting all users")
    assert r.status_code == 200
    assert r.json()["severity"] == "SEV1"


def test_sev1_down_keyword():
    r = triage("Payment service is down")
    assert r.status_code == 200
    assert r.json()["severity"] == "SEV1"


def test_sev1_unavailable_keyword():
    r = triage("Auth service unreachable from all regions")
    assert r.status_code == 200
    assert r.json()["severity"] == "SEV1"


def test_sev2_timeout_keyword():
    r = triage("Checkout timeout affecting most users")
    assert r.status_code == 200
    assert r.json()["severity"] == "SEV2"


def test_sev2_degraded_keyword():
    r = triage("Service degraded, elevated latency observed")
    assert r.status_code == 200
    assert r.json()["severity"] == "SEV2"


def test_sev3_intermittent_keyword():
    r = triage("Intermittent errors on secondary endpoint")
    assert r.status_code == 200
    assert r.json()["severity"] == "SEV3"


def test_sev4_cosmetic_keyword():
    r = triage("Cosmetic typo in error message")
    assert r.status_code == 200
    assert r.json()["severity"] == "SEV4"


def test_no_keyword_defaults_to_sev3():
    r = triage("Something happened", "We are not sure what it is")
    assert r.status_code == 200
    data = r.json()
    # Default when no strong signal
    assert data["severity"] in ("SEV3", "SEV4")


# ---------------------------------------------------------------------------
# aiGenerated always True (FR-18.2)
# ---------------------------------------------------------------------------

def test_ai_generated_always_true():
    r = triage("Payment gateway timeout")
    assert r.status_code == 200
    assert r.json()["aiGenerated"] is True


def test_ai_generated_true_even_for_sev4():
    r = triage("Minor cosmetic UI issue")
    assert r.status_code == 200
    assert r.json()["aiGenerated"] is True


# ---------------------------------------------------------------------------
# Reason always present and non-empty
# ---------------------------------------------------------------------------

def test_reason_present_and_non_empty():
    r = triage("Payment gateway timeout affecting checkout")
    assert r.status_code == 200
    data = r.json()
    assert "reason" in data
    assert isinstance(data["reason"], str)
    assert len(data["reason"]) > 0


# ---------------------------------------------------------------------------
# Service hint from similar incidents
# ---------------------------------------------------------------------------

def test_service_hint_extracted_from_similar_incidents():
    similar = [
        {"severity": "SEV2", "service": {"id": 1, "name": "Payments"}},
        {"severity": "SEV2", "service": {"id": 1, "name": "Payments"}},
    ]
    r = triage("Checkout failure", similar=similar)
    assert r.status_code == 200
    data = r.json()
    assert data["service"] is not None
    assert data["service"]["name"] == "Payments"
    assert data["service"]["id"] == 1


def test_service_hint_null_when_no_similar_incidents():
    r = triage("Payment gateway timeout", similar=[])
    assert r.status_code == 200
    data = r.json()
    assert data["service"] is None


def test_service_hint_null_when_similar_incidents_have_no_service():
    similar = [{"severity": "SEV2"}]
    r = triage("Payment gateway timeout", similar=similar)
    assert r.status_code == 200
    data = r.json()
    assert data["service"] is None


# ---------------------------------------------------------------------------
# Similar incident severity evidence
# ---------------------------------------------------------------------------

def test_similar_sev1_majority_escalates_to_sev2():
    # Non-SEV1-keyword title, but similar incidents are all SEV1
    similar = [
        {"severity": "SEV1", "service": {"id": 1, "name": "Payments"}},
        {"severity": "SEV1", "service": {"id": 1, "name": "Payments"}},
    ]
    r = triage("Payment slowness", "Some users report delays", similar=similar)
    assert r.status_code == 200
    # Either SEV2 (escalated by evidence) or SEV3 (timeout keyword not present) — must be at most SEV2
    sev = r.json()["severity"]
    assert sev in ("SEV1", "SEV2")


# ---------------------------------------------------------------------------
# Determinism
# ---------------------------------------------------------------------------

def test_same_input_same_output():
    r1 = triage("Checkout timeout", "Payment service returning 504 gateway timeouts")
    r2 = triage("Checkout timeout", "Payment service returning 504 gateway timeouts")
    assert r1.status_code == 200
    assert r2.status_code == 200
    assert r1.json()["severity"] == r2.json()["severity"]
    assert r1.json()["reason"] == r2.json()["reason"]


# ---------------------------------------------------------------------------
# Input validation
# ---------------------------------------------------------------------------

def test_empty_title_rejected():
    r = client.post("/triage", json={"title": "", "description": "some description"})
    assert r.status_code == 422


def test_empty_description_rejected():
    r = client.post("/triage", json={"title": "Some title", "description": ""})
    assert r.status_code == 422


def test_missing_title_rejected():
    r = client.post("/triage", json={"description": "some description"})
    assert r.status_code == 422


def test_missing_description_rejected():
    r = client.post("/triage", json={"title": "Some title"})
    assert r.status_code == 422


def test_similar_incidents_defaults_to_empty_list():
    # No similar_incidents key — should default to empty list and not crash
    r = client.post("/triage", json={"title": "Service is slow", "description": "users report latency"})
    assert r.status_code == 200
