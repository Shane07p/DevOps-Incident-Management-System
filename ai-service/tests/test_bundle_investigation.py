"""Tests for live backend EvidenceBundle tools and /investigate with evidenceBundle (Issue #242)."""

from __future__ import annotations

import json
from fastapi.testclient import TestClient

from agent_tools import EvidenceBundle, build_bundle_tool_registry
from main import app
from providers import MockProvider, ModelTurnResponse, ToolCallRequest


def test_bundle_tools_read_from_supplied_evidence_bundle():
    bundle = EvidenceBundle(
        incident={
            "id": 10,
            "reference": "INC-10",
            "title": "Checkout 500 spike",
            "description": "NullPointerException in checkout",
            "service": "Checkout",
            "severity": "SEV1",
            "status": "OPEN",
        },
        timeline=[
            {
                "id": 1,
                "eventType": "INCIDENT_CREATED",
                "actor": "alice",
                "details": "Reported as SEV1 on Checkout",
                "recordedAt": "2026-11-10T10:00:00Z",
            }
        ],
        deploys=[
            {
                "id": 7,
                "service": "Checkout",
                "version": "v2.4.1-bad",
                "commit": "abc1234",
                "author": "ci-bot",
                "deployedAt": "2026-11-10T09:50:00Z",
            }
        ],
        logs=[
            {
                "id": "1",
                "timestamp": "2026-11-10T10:00:05Z",
                "level": "ERROR",
                "message": "NullPointerException in CartHandler",
                "attributes": {},
            },
            {
                "id": "2",
                "timestamp": "2026-11-10T10:00:06Z",
                "level": "ERROR",
                "message": "NullPointerException in CartHandler",
                "attributes": {},
            },
        ],
        metrics=[
            {
                "metric": "errorRate",
                "serviceName": "Checkout",
                "points": [
                    {"timestamp": "2026-11-10T09:55:00Z", "value": 0.01},
                    {"timestamp": "2026-11-10T10:00:00Z", "value": 0.85},
                ],
            }
        ],
    )

    registry = build_bundle_tool_registry(bundle)

    log_res = registry.execute("query_log_patterns", {"service": "checkout"})
    assert log_res.success is True
    assert len(log_res.evidence_items) == 1
    assert "NullPointerException in CartHandler" in log_res.evidence_items[0].content
    assert "2 time(s)" in log_res.evidence_items[0].content

    dep_res = registry.execute("query_deploy_events", {"service": "checkout"})
    assert dep_res.success is True
    assert len(dep_res.evidence_items) == 1
    assert "v2.4.1-bad" in dep_res.evidence_items[0].content

    metric_res = registry.execute("query_metric_anomalies", {"service": "checkout"})
    assert metric_res.success is True
    assert len(metric_res.evidence_items) == 1
    assert "errorRate" in metric_res.evidence_items[0].content

    timeline_res = registry.execute("query_timeline_entries", {})
    assert timeline_res.success is True
    assert len(timeline_res.evidence_items) == 1
    assert "INCIDENT_CREATED" in timeline_res.evidence_items[0].content

    health_res = registry.execute("get_service_health", {"service": "checkout"})
    assert health_res.success is True
    assert "INC-10" in health_res.evidence_items[0].content


def test_post_investigate_uses_evidence_bundle_and_returns_suggested_actions():
    client = TestClient(app)
    provider = MockProvider(
        responses=[
            ModelTurnResponse(
                tool_calls=[
                    ToolCallRequest(
                        call_id="c1",
                        tool_name="query_deploy_events",
                        arguments={"service": "checkout"},
                    )
                ],
                total_tokens=40,
            ),
            ModelTurnResponse(
                content=json.dumps(
                    {
                        "summary": "Recent deploy v2.4.1-bad caused checkout failures.",
                        "hypotheses": [
                            {
                                "hypothesis": "Deploy v2.4.1-bad introduced NullPointerException",
                                "confidence": 0.9,
                                "evidenceRefs": ["ev-deploy-0"],
                                "reasoning": "Deployed 10 minutes before incident",
                            }
                        ],
                        "suggestedActions": [
                            "Roll back checkout to v2.4.0",
                            "Verify checkout error rate drops below 1%",
                        ],
                    }
                ),
                total_tokens=60,
            ),
        ]
    )
    app.state.agent_provider = provider
    try:
        resp = client.post(
            "/investigate",
            json={
                "service": "checkout",
                "incidentStart": "2026-11-10T10:00:00Z",
                "description": "500 errors",
                "evidenceBundle": {
                    "incident": {"id": 10, "reference": "INC-10", "title": "Checkout 500s"},
                    "timeline": [],
                    "deploys": [
                        {
                            "id": 7,
                            "service": "Checkout",
                            "version": "v2.4.1-bad",
                            "commit": "abc1234",
                            "author": "ci-bot",
                            "deployedAt": "2026-11-10T09:50:00Z",
                        }
                    ],
                    "logs": [],
                    "metrics": [],
                },
            },
        )
    finally:
        state_dict = getattr(app.state, "_state", None)
        if isinstance(state_dict, dict):
            state_dict.pop("agent_provider", None)

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "completed"
    assert len(body["hypotheses"]) == 1
    assert body["hypotheses"][0]["evidenceRefs"] == ["ev-deploy-0"]
    assert "v2.4.1-bad" in body["evidence"][0]["content"]
    assert body["suggestedActions"] == [
        "Roll back checkout to v2.4.0",
        "Verify checkout error rate drops below 1%",
    ]
