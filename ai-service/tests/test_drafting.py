"""Tests for POST /draft endpoint and reference sanitization (Issue #246)."""

from __future__ import annotations

import json
from fastapi.testclient import TestClient

from main import app
from providers import MockProvider, ModelTurnResponse


def _cleanup_provider():
    state_dict = getattr(app.state, "_state", None)
    if isinstance(state_dict, dict):
        state_dict.pop("agent_provider", None)


def _sample_payload(kind: str) -> dict:
    return {
        "kind": kind,
        "incident": {
            "id": 1,
            "reference": "INC-1",
            "title": "Checkout 500 spike",
            "description": "NullPointerException in checkout",
            "service": "Checkout",
            "severity": "SEV1",
            "status": "RESOLVED",
        },
        "timeline": [
            {
                "id": 10,
                "eventType": "INCIDENT_CREATED",
                "actor": "alice",
                "details": "Reported SEV1 on Checkout",
                "recordedAt": "2026-11-10T10:00:00Z",
            }
        ],
        "acceptedHypotheses": [
            {"title": "Bad deploy v1.0.1-broken", "confidence": 0.9}
        ],
        "followUps": ["Add pre-deploy smoke check"],
    }


def test_draft_summary_and_status_update_shapes():
    client = TestClient(app)
    for kind in ("SUMMARY", "STATUS_UPDATE"):
        app.state.agent_provider = MockProvider(
            responses=[
                ModelTurnResponse(
                    content=json.dumps({"text": f"{kind} for INC-1 referencing entry #10."}),
                    total_tokens=20,
                )
            ]
        )
        try:
            resp = client.post("/draft", json=_sample_payload(kind))
        finally:
            _cleanup_provider()

        assert resp.status_code == 200
        body = resp.json()
        assert body["aiGenerated"] is True
        assert body["kind"] == kind
        assert "INC-1" in body["text"]
        assert body["postmortem"] is None


def test_draft_postmortem_shape():
    client = TestClient(app)
    app.state.agent_provider = MockProvider(
        responses=[
            ModelTurnResponse(
                content=json.dumps(
                    {
                        "text": "Postmortem draft for INC-1.",
                        "postmortem": {
                            "summary": "INC-1 caused checkout 500s.",
                            "impact": "Checkout errors affected 80% of requests.",
                            "rootCause": "Faulty deploy v1.0.1-broken.",
                            "resolution": "Rolled back to v1.0.0.",
                            "wentWell": "Fast detection via alert.",
                            "wentBadly": "Smoke test did not catch NPE.",
                        },
                    }
                ),
                total_tokens=50,
            )
        ]
    )
    try:
        resp = client.post("/draft", json=_sample_payload("POSTMORTEM"))
    finally:
        _cleanup_provider()

    assert resp.status_code == 200
    body = resp.json()
    assert body["kind"] == "POSTMORTEM"
    assert body["postmortem"] == {
        "summary": "INC-1 caused checkout 500s.",
        "impact": "Checkout errors affected 80% of requests.",
        "rootCause": "Faulty deploy v1.0.1-broken.",
        "resolution": "Rolled back to v1.0.0.",
        "wentWell": "Fast detection via alert.",
        "wentBadly": "Smoke test did not catch NPE.",
    }


def test_draft_drops_invented_references():
    client = TestClient(app)
    app.state.agent_provider = MockProvider(
        responses=[
            ModelTurnResponse(
                content=json.dumps(
                    {
                        "text": "INC-1 matches INC-999 and entry #999, confirmed by entry #10."
                    }
                ),
                total_tokens=30,
            )
        ]
    )
    try:
        resp = client.post("/draft", json=_sample_payload("SUMMARY"))
    finally:
        _cleanup_provider()

    assert resp.status_code == 200
    text = resp.json()["text"]
    assert "INC-1" in text
    assert "entry #10" in text
    assert "INC-999" not in text
    assert "999" not in text


def test_draft_returns_503_when_provider_fails():
    client = TestClient(app)
    app.state.agent_provider = MockProvider(responses=[RuntimeError("Ollama down")])
    try:
        resp = client.post("/draft", json=_sample_payload("SUMMARY"))
    finally:
        _cleanup_provider()

    assert resp.status_code == 503
