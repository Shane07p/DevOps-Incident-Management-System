"""
Malformed model answers, hard limits, and time-bounded evidence (NFR-12.2; Codex review R08-R10).
The model's output is untrusted: a bad answer is dropped, never shown as a confident hypothesis or
turned into a server error, and an investigation stops at its deadline and token budget.
"""
from __future__ import annotations

import asyncio
import json

import pytest

from agent_loop import AgentInvestigationLoop, AgentLimits, InvestigationRequest
from agent_tools import build_default_tool_registry, query_metric_anomalies
from providers import MockProvider, ModelTurnResponse, ToolCallRequest


@pytest.fixture
def anyio_backend():
    return "asyncio"


REQUEST = InvestigationRequest(service="payments", incident_start="2026-10-27T08:16:07Z")


def _metric_call() -> ModelTurnResponse:
    return ModelTurnResponse(
        content="Checking metrics.",
        tool_calls=[ToolCallRequest(call_id="call-1", tool_name="query_metric_anomalies",
                                    arguments={"service": "payments"})],
        total_tokens=10,
    )


def _answer(body: object) -> ModelTurnResponse:
    return ModelTurnResponse(content=json.dumps(body), tool_calls=[], total_tokens=10)


async def _investigate(*turns: ModelTurnResponse, limits: AgentLimits | None = None):
    loop = AgentInvestigationLoop(
        provider=MockProvider(responses=list(turns)),
        tool_registry=build_default_tool_registry(),
        default_limits=limits or AgentLimits(max_steps=5, timeout_seconds=10.0, max_tokens=1000),
    )
    return await loop.run(REQUEST)


# ---- R08: malformed answers ------------------------------------------------------------------

@pytest.mark.anyio
async def test_a_summary_that_is_not_text_does_not_crash_the_investigation():
    res = await _investigate(_metric_call(), _answer({"summary": {"text": "nested"}, "hypotheses": []}))

    assert res.status == "completed"
    assert isinstance(res.summary, str)


@pytest.mark.anyio
async def test_reasoning_that_is_not_text_is_left_out():
    res = await _investigate(_metric_call(), _answer({"summary": "Gateway slow", "hypotheses": [
        {"hypothesis": "Gateway slowdown", "confidence": 0.7, "evidenceRefs": ["ev-metric-0"],
         "reasoning": {"why": "nested"}},
    ]}))

    assert [h.hypothesis for h in res.hypotheses] == ["Gateway slowdown"]
    assert res.hypotheses[0].reasoning is None


@pytest.mark.anyio
async def test_a_missing_hypothesis_with_nan_confidence_is_dropped():
    # json.dumps writes NaN, as a model might.
    res = await _investigate(_metric_call(), _answer({"summary": "x", "hypotheses": [
        {"hypothesis": None, "confidence": float("nan"), "evidenceRefs": ["ev-metric-0"]},
        {"hypothesis": "Real one", "confidence": "NaN", "evidenceRefs": ["ev-metric-0"]},
    ]}))

    assert res.hypotheses == []


# ---- R09: hard limits ----------------------------------------------------------------------

@pytest.mark.anyio
async def test_a_provider_that_ignores_its_timeout_is_cut_off_at_the_deadline():
    class Trickling(MockProvider):
        async def generate(self, messages, tools, timeout=10.0, max_output_tokens=None):
            await asyncio.sleep(5)  # a slow stream that keeps the connection busy
            return _answer({"summary": "late", "hypotheses": []})

    loop = AgentInvestigationLoop(
        provider=Trickling(),
        tool_registry=build_default_tool_registry(),
        default_limits=AgentLimits(max_steps=5, timeout_seconds=0.1, max_tokens=1000),
    )
    started = asyncio.get_running_loop().time()

    res = await loop.run(REQUEST)

    assert res.status == "timeout"
    assert asyncio.get_running_loop().time() - started < 1.0


@pytest.mark.anyio
async def test_each_turn_may_generate_only_the_tokens_left_in_the_budget():
    provider = MockProvider(responses=[_metric_call(), _answer({"summary": "done", "hypotheses": []})])
    loop = AgentInvestigationLoop(
        provider=provider,
        tool_registry=build_default_tool_registry(),
        default_limits=AgentLimits(max_steps=5, timeout_seconds=10.0, max_tokens=100),
    )

    await loop.run(REQUEST)

    assert [call["max_output_tokens"] for call in provider.call_history] == [100, 90]


# ---- R10: metric evidence within the incident's time range -------------------------------

def test_metric_anomalies_within_a_window_come_from_that_scenario_only():
    res = query_metric_anomalies("payments", start_time="2026-10-27T08:00:00Z", end_time="2026-10-27T08:30:00Z")

    assert res.success is True
    assert [m["scenario_id"] for m in res.data["anomalies"]] == ["SCN-01"]


def test_metric_anomalies_outside_every_scenario_are_empty():
    res = query_metric_anomalies("payments", start_time="2099-01-01T00:00:00Z")

    assert res.success is True
    assert res.data["anomalies"] == []
    assert res.evidence_items == []


def test_metric_anomalies_refuse_a_time_that_is_not_one():
    res = query_metric_anomalies("payments", start_time="not-a-date")

    assert res.success is False
    assert "Invalid timestamp" in res.error
