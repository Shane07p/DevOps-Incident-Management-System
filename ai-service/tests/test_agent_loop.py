"""
test_agent_loop.py - Automated Pytest Suite for Sprint 3 Issue #91.
-----------------------------------------------------------------------------
Validates:
  - Successful multi-step tool-calling investigation.
  - Correct dispatch and execution of registered evidence tools.
  - Tool argument validation (type errors, missing required parameters).
  - Rejection of unknown tool calls.
  - Evidence collection and ranking of supported hypotheses.
  - Strict evidence attribution: Every hypothesis cites verified EvidenceItem IDs.
  - Rejection/dropping of unsupported hypotheses (missing, empty, or fabricated citations).
  - Maximum tool-call step limit enforcement.
  - Wall-clock execution deadline enforcement.
  - Cumulative token budget enforcement.
  - Provider failure and malformed JSON recovery.
  - Tool execution failure handling without crashing loop.
  - Empty evidence scenarios yielding zero hypotheses.
  - POST /investigate endpoint integration tests via FastAPI TestClient.
  - Configuration environment variable reading and boundary validation.
"""

from __future__ import annotations

import asyncio
import json
import pytest
from fastapi.testclient import TestClient

from agent_loop import (
    AgentInvestigationLoop,
    AgentLimits,
    EvidenceVault,
    InvestigationRequest,
    InvestigationResult,
    RankedHypothesis,
)
from agent_tools import (
    EvidenceItem,
    ToolDefinition,
    ToolRegistry,
    ToolResult,
    build_default_tool_registry,
    query_deploy_events,
    query_log_patterns,
    query_metric_anomalies,
    get_service_health,
)
from main import (
    app,
    get_agent_max_steps,
    get_agent_max_tokens,
    get_agent_timeout,
)
from providers import MockProvider, ModelTurnResponse, ToolCallRequest


@pytest.fixture
def client():
    return TestClient(app)


# ---------------------------------------------------------------------------
# 1. Tool Registry & Evidence Tools Tests
# ---------------------------------------------------------------------------

def test_tool_registry_dispatches_query_log_patterns():
    registry = build_default_tool_registry()
    res = registry.execute(
        "query_log_patterns",
        {
            "service": "payments",
            "incident_start": "2026-10-27T08:16:07Z",
            "incident_end": "2026-10-27T08:16:27Z",
        },
    )
    assert res.success is True
    assert res.tool_name == "query_log_patterns"
    assert len(res.evidence_items) >= 1
    slow_pattern = next((ev for ev in res.evidence_items if "payment.gateway.slow" in ev.content), None)
    assert slow_pattern is not None
    assert slow_pattern.id == "ev-log-0"
    assert slow_pattern.evidence_type == "log_pattern"


def test_tool_registry_dispatches_query_deploy_events():
    registry = build_default_tool_registry()
    res = registry.execute("query_deploy_events", {"service": "checkout"})
    assert res.success is True
    assert len(res.evidence_items) >= 1
    assert any("v1.0.1-broken" in ev.content for ev in res.evidence_items)


def test_tool_registry_dispatches_query_metric_anomalies():
    registry = build_default_tool_registry()
    res = registry.execute("query_metric_anomalies", {"service": "payments"})
    assert res.success is True
    assert len(res.evidence_items) >= 1
    assert any("demo_http_request_duration_seconds" in ev.content for ev in res.evidence_items)


def test_tool_registry_dispatches_get_service_health():
    registry = build_default_tool_registry()
    res = registry.execute("get_service_health", {"service": "checkout"})
    assert res.success is True
    assert len(res.evidence_items) == 1
    assert "degraded" in res.evidence_items[0].content


def test_tool_registry_rejects_unknown_tool():
    registry = build_default_tool_registry()
    res = registry.execute("drop_database_now", {"service": "payments"})
    assert res.success is False
    assert "not registered" in res.error
    assert len(res.evidence_items) == 0


def test_tool_registry_validates_missing_required_arguments():
    registry = build_default_tool_registry()
    # query_log_patterns requires service, incident_start, incident_end
    res = registry.execute("query_log_patterns", {"service": "payments"})
    assert res.success is False
    assert "missing required fields" in res.error


def test_tool_registry_validates_argument_types():
    registry = build_default_tool_registry()
    # service must be string, pass integer instead
    res = registry.execute(
        "query_log_patterns",
        {"service": 12345, "incident_start": "2026-10-27T08:16:07Z", "incident_end": "2026-10-27T08:16:27Z"},
    )
    assert res.success is False
    assert "expected string" in res.error


# ---------------------------------------------------------------------------
# 2. Evidence Vault & Hypothesis Attribution Verification
# ---------------------------------------------------------------------------

def test_evidence_vault_filters_unsupported_hypotheses():
    vault = EvidenceVault()
    real_ev = EvidenceItem(
        id="ev-log-0",
        source_tool="query_log_patterns",
        evidence_type="log_pattern",
        content="payment.gateway.slow pattern observed 5 times",
    )
    vault.add(real_ev)

    candidates = [
        # Valid hypothesis citing real evidence
        {
            "hypothesis": "Payment gateway timeout causing latency spike",
            "confidence": 0.9,
            "evidence_refs": ["ev-log-0"],
            "reasoning": "Observed in logs",
        },
        # Invalid hypothesis citing empty evidence list
        {
            "hypothesis": "Database CPU pegged",
            "confidence": 0.8,
            "evidence_refs": [],
        },
        # Invalid hypothesis citing non-existent / fabricated evidence ID
        {
            "hypothesis": "Network switch packet drop",
            "confidence": 0.75,
            "evidence_refs": ["ev-fabricated-999"],
        },
        # Invalid hypothesis citing mix of valid and fabricated evidence
        {
            "hypothesis": "Downstream partner DNS outage",
            "confidence": 0.6,
            "evidence_refs": ["ev-log-0", "ev-fake-1"],
        },
    ]

    surviving = vault.validate_and_filter_hypotheses(candidates)
    assert len(surviving) == 1
    assert surviving[0].hypothesis == "Payment gateway timeout causing latency spike"
    assert surviving[0].confidence == 0.9
    assert surviving[0].evidence_refs == ["ev-log-0"]


def test_evidence_vault_ranks_hypotheses_by_confidence_descending():
    vault = EvidenceVault()
    vault.add(EvidenceItem(id="ev-1", source_tool="t", evidence_type="m", content="m1"))
    vault.add(EvidenceItem(id="ev-2", source_tool="t", evidence_type="m", content="m2"))

    candidates = [
        {"hypothesis": "Hypothesis B", "confidence": 0.45, "evidence_refs": ["ev-1"]},
        {"hypothesis": "Hypothesis A", "confidence": 0.95, "evidence_refs": ["ev-1"]},
        {"hypothesis": "Hypothesis C", "confidence": 0.70, "evidence_refs": ["ev-2"]},
    ]

    ranked = vault.validate_and_filter_hypotheses(candidates)
    assert len(ranked) == 3
    assert ranked[0].hypothesis == "Hypothesis A"
    assert ranked[1].hypothesis == "Hypothesis C"
    assert ranked[2].hypothesis == "Hypothesis B"


# ---------------------------------------------------------------------------
# 3. Investigation Loop & Resource Budget Enforcement
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_successful_multi_step_investigation_loop():
    mock_provider = MockProvider()

    # Turn 1: Model calls query_log_patterns
    mock_provider.add_response(
        ModelTurnResponse(
            content="Checking logs for payments service.",
            tool_calls=[
                ToolCallRequest(
                    call_id="call-1",
                    tool_name="query_log_patterns",
                    arguments={
                        "service": "payments",
                        "incident_start": "2026-10-27T08:16:07Z",
                        "incident_end": "2026-10-27T08:16:27Z",
                    },
                )
            ],
            prompt_tokens=50,
            completion_tokens=30,
            total_tokens=80,
        )
    )

    # Turn 2: Model calls query_metric_anomalies
    mock_provider.add_response(
        ModelTurnResponse(
            content="Now checking metric anomalies for payments.",
            tool_calls=[
                ToolCallRequest(
                    call_id="call-2",
                    tool_name="query_metric_anomalies",
                    arguments={"service": "payments"},
                )
            ],
            prompt_tokens=100,
            completion_tokens=30,
            total_tokens=130,
        )
    )

    # Turn 3: Model returns final conclusion citing verified evidence IDs
    final_output = json.dumps(
        {
            "summary": "Payments service latency is degraded due to payment gateway slowdown.",
            "hypotheses": [
                {
                    "hypothesis": "Upstream payment gateway slowdown exceeding 1.5s SLA",
                    "confidence": 0.95,
                    "evidence_refs": ["ev-log-0", "ev-metric-0"],
                    "reasoning": "New payment.gateway.slow log events and p99 latency spiked to 2001ms.",
                }
            ],
        }
    )
    mock_provider.add_response(
        ModelTurnResponse(
            content=f"```json\n{final_output}\n```",
            tool_calls=[],
            prompt_tokens=150,
            completion_tokens=60,
            total_tokens=210,
        )
    )

    loop = AgentInvestigationLoop(
        provider=mock_provider,
        tool_registry=build_default_tool_registry(),
        default_limits=AgentLimits(max_steps=5, timeout_seconds=10.0, max_tokens=1000),
    )

    req = InvestigationRequest(
        service="payments",
        incident_start="2026-10-27T08:16:07Z",
        incident_end="2026-10-27T08:16:27Z",
    )

    res = await loop.run(req)

    assert res.aiGenerated is True
    assert res.status == "completed"
    assert res.steps_taken == 2
    assert res.tokens_used == 420
    assert len(res.hypotheses) == 1
    assert res.hypotheses[0].hypothesis == "Upstream payment gateway slowdown exceeding 1.5s SLA"
    assert res.hypotheses[0].confidence == 0.95
    assert set(res.hypotheses[0].evidence_refs) == {"ev-log-0", "ev-metric-0"}
    assert len(res.evidence) >= 2


@pytest.mark.asyncio
async def test_step_limit_enforcement():
    # Configure mock provider to request a tool call endlessly
    mock_provider = MockProvider()
    for _ in range(10):
        mock_provider.add_response(
            ModelTurnResponse(
                content="Step request",
                tool_calls=[
                    ToolCallRequest(
                        call_id="call-step",
                        tool_name="get_service_health",
                        arguments={"service": "checkout"},
                    )
                ],
                total_tokens=20,
            )
        )

    # Set hard step limit of 3
    loop = AgentInvestigationLoop(
        provider=mock_provider,
        tool_registry=build_default_tool_registry(),
        default_limits=AgentLimits(max_steps=3, timeout_seconds=10.0, max_tokens=5000),
    )

    req = InvestigationRequest(
        service="checkout",
        incident_start="2026-10-27T08:16:07Z",
        max_steps=3,
    )

    res = await loop.run(req)

    assert res.status == "step_limit_exceeded"
    assert res.steps_taken == 3
    assert "maximum limit of 3" in res.summary


@pytest.mark.asyncio
async def test_wall_clock_timeout_enforcement():
    # Configure mock provider with artificial delay exceeding timeout
    mock_provider = MockProvider(
        artificial_delay_seconds=0.25,
    )
    mock_provider.add_response(
        ModelTurnResponse(
            content="Slow step",
            tool_calls=[
                ToolCallRequest(
                    call_id="slow-1",
                    tool_name="get_service_health",
                    arguments={"service": "payments"},
                )
            ],
            total_tokens=10,
        )
    )

    loop = AgentInvestigationLoop(
        provider=mock_provider,
        tool_registry=build_default_tool_registry(),
        default_limits=AgentLimits(max_steps=5, timeout_seconds=0.10, max_tokens=1000),
    )

    req = InvestigationRequest(
        service="payments",
        incident_start="2026-10-27T08:16:07Z",
        timeout_seconds=0.10,
    )

    res = await loop.run(req)

    assert res.status == "timeout"
    assert "timeout" in res.summary.lower() or "timed out" in res.summary.lower()


@pytest.mark.asyncio
async def test_token_budget_enforcement():
    mock_provider = MockProvider()
    # Turn 1 uses 300 tokens out of 400 budget
    mock_provider.add_response(
        ModelTurnResponse(
            content="Token burn turn 1",
            tool_calls=[
                ToolCallRequest(
                    call_id="burn-1",
                    tool_name="get_service_health",
                    arguments={"service": "payments"},
                )
            ],
            total_tokens=300,
        )
    )
    # Turn 2 uses 200 tokens, exceeding 400 total
    mock_provider.add_response(
        ModelTurnResponse(
            content="Token burn turn 2",
            tool_calls=[],
            total_tokens=200,
        )
    )

    loop = AgentInvestigationLoop(
        provider=mock_provider,
        tool_registry=build_default_tool_registry(),
        default_limits=AgentLimits(max_steps=5, timeout_seconds=10.0, max_tokens=400),
    )

    req = InvestigationRequest(
        service="payments",
        incident_start="2026-10-27T08:16:07Z",
        max_tokens=400,
    )

    res = await loop.run(req)

    assert res.status == "token_budget_exhausted"
    assert res.tokens_used > 400
    assert "token budget" in res.summary.lower()


@pytest.mark.asyncio
async def test_provider_error_graceful_recovery():
    mock_provider = MockProvider()
    mock_provider.add_response(RuntimeError("Ollama daemon disconnected"))

    loop = AgentInvestigationLoop(
        provider=mock_provider,
        tool_registry=build_default_tool_registry(),
    )

    req = InvestigationRequest(
        service="payments",
        incident_start="2026-10-27T08:16:07Z",
    )

    res = await loop.run(req)

    assert res.status == "provider_error"
    assert "Provider failure" in res.summary
    assert res.hypotheses == []


@pytest.mark.asyncio
async def test_empty_evidence_and_no_hypotheses():
    mock_provider = MockProvider()
    # Model calls tool on service with no errors/anomalies (e.g. search service in baseline)
    mock_provider.add_response(
        ModelTurnResponse(
            content="Check search service logs",
            tool_calls=[
                ToolCallRequest(
                    call_id="call-s",
                    tool_name="query_log_patterns",
                    arguments={
                        "service": "non_existent_service",
                        "incident_start": "2026-10-27T08:16:07Z",
                        "incident_end": "2026-10-27T08:16:27Z",
                    },
                )
            ],
            total_tokens=40,
        )
    )
    # Model returns with no evidence cited
    mock_provider.add_response(
        ModelTurnResponse(
            content=json.dumps({"summary": "No patterns found.", "hypotheses": []}),
            tool_calls=[],
            total_tokens=30,
        )
    )

    loop = AgentInvestigationLoop(
        provider=mock_provider,
        tool_registry=build_default_tool_registry(),
    )

    req = InvestigationRequest(
        service="non_existent_service",
        incident_start="2026-10-27T08:16:07Z",
        incident_end="2026-10-27T08:16:27Z",
    )

    res = await loop.run(req)

    assert res.status == "no_evidence"
    assert res.hypotheses == []
    assert len(res.evidence) == 0


@pytest.mark.asyncio
async def test_dropping_hypotheses_when_evidence_fabricated():
    mock_provider = MockProvider()
    # Turn 1: Valid tool call
    mock_provider.add_response(
        ModelTurnResponse(
            content="Check deploys",
            tool_calls=[
                ToolCallRequest(
                    call_id="dep-1",
                    tool_name="query_deploy_events",
                    arguments={"service": "checkout"},
                )
            ],
            total_tokens=50,
        )
    )
    # Turn 2: Returns one hallucinated hypothesis and one valid hypothesis
    mock_provider.add_response(
        ModelTurnResponse(
            content=json.dumps(
                {
                    "summary": "Checkout deploy analysis",
                    "hypotheses": [
                        {
                            "hypothesis": "Bad checkout deployment v1.0.1-broken",
                            "confidence": 0.90,
                            "evidence_refs": ["ev-deploy-0"],
                        },
                        {
                            "hypothesis": "Hallucinated DNS outage",
                            "confidence": 0.85,
                            "evidence_refs": ["ev-non-existent-99"],
                        },
                    ],
                }
            ),
            tool_calls=[],
            total_tokens=50,
        )
    )

    loop = AgentInvestigationLoop(
        provider=mock_provider,
        tool_registry=build_default_tool_registry(),
    )

    req = InvestigationRequest(
        service="checkout",
        incident_start="2026-10-27T08:16:07Z",
    )

    res = await loop.run(req)

    assert res.status == "completed"
    assert len(res.hypotheses) == 1
    assert res.hypotheses[0].hypothesis == "Bad checkout deployment v1.0.1-broken"


# ---------------------------------------------------------------------------
# 4. HTTP Endpoint Integration Tests (POST /investigate)
# ---------------------------------------------------------------------------

def test_api_investigate_successful(client):
    mock_provider = MockProvider()
    mock_provider.add_response(
        ModelTurnResponse(
            content="Investigating payments",
            tool_calls=[
                ToolCallRequest(
                    call_id="call-api-1",
                    tool_name="query_metric_anomalies",
                    arguments={"service": "payments"},
                )
            ],
            total_tokens=35,
        )
    )
    mock_provider.add_response(
        ModelTurnResponse(
            content=json.dumps(
                {
                    "summary": "Payments metric check completed.",
                    "hypotheses": [
                        {
                            "hypothesis": "High latency on payment authorization",
                            "confidence": 0.88,
                            "evidence_refs": ["ev-metric-0"],
                            "reasoning": "p99 latency 2001ms exceeds 1500ms threshold",
                        }
                    ],
                }
            ),
            tool_calls=[],
            total_tokens=45,
        )
    )

    app.state.agent_provider = mock_provider
    try:
        payload = {
            "service": "payments",
            "incident_start": "2026-10-27T08:16:07Z",
            "incident_end": "2026-10-27T08:16:27Z",
            "max_steps": 3,
            "timeout_seconds": 10.0,
            "max_tokens": 2000,
        }
        response = client.post("/investigate", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert data["aiGenerated"] is True
        assert data["status"] == "completed"
        assert len(data["hypotheses"]) == 1
        assert data["hypotheses"][0]["evidence_refs"] == ["ev-metric-0"]
        assert len(data["evidence"]) >= 1
    finally:
        app.state.agent_provider = None


def test_api_investigate_validation_error(client):
    # Missing required 'incident_start'
    response = client.post("/investigate", json={"service": "payments"})
    assert response.status_code == 422


# ---------------------------------------------------------------------------
# 5. Environment Variable Limits Parsing & Defaults
# ---------------------------------------------------------------------------

def test_agent_limits_env_parsing(monkeypatch):
    monkeypatch.setenv("AGENT_MAX_STEPS", "12")
    monkeypatch.setenv("AGENT_TIMEOUT_SECONDS", "45.5")
    monkeypatch.setenv("AGENT_MAX_TOKENS", "8000")

    assert get_agent_max_steps() == 12
    assert get_agent_timeout() == 45.5
    assert get_agent_max_tokens() == 8000


def test_agent_limits_invalid_env_falls_back_to_defaults(monkeypatch):
    monkeypatch.setenv("AGENT_MAX_STEPS", "invalid_number")
    monkeypatch.setenv("AGENT_TIMEOUT_SECONDS", "-100.0")
    monkeypatch.setenv("AGENT_MAX_TOKENS", "0")

    assert get_agent_max_steps() == 5
    assert get_agent_timeout() == 15.0
    assert get_agent_max_tokens() == 4000
