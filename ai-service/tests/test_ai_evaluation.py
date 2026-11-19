"""Tests for AI evaluation runner (Issue #252, Sprint 6).

Hermetic unit tests using scripted/mock providers and deterministic assertions.
No live Ollama instance required.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

from drafting import DraftRequest, DraftResponse, sanitize_draft_text, generate_draft
from main import _classify_severity
from providers import MockProvider, ModelTurnResponse, ToolCallRequest
from ai_evaluation import (
    ScenarioEvaluatorProvider,
    DraftingAdversarialProvider,
    check_cause_match,
    evaluate_ai4_triage,
)

DATA_DIR = Path(__file__).resolve().parents[1] / "data"
TRIAGE_EVAL_FILE = DATA_DIR / "triage_eval_set.jsonl"


# ---------------------------------------------------------------------------
# AI-4 Triage: Unit Tests (Scoring PR #283's Triage Service)
# ---------------------------------------------------------------------------


class TestTriageClassifier:
    """Deterministic rule-based severity classification tests using PR #283's classifier."""

    def test_sev1_total_outage(self):
        sev, reason = _classify_severity("Payment gateway total outage", "All payments down globally.", [])
        assert sev == "SEV1"

    def test_sev1_data_loss(self):
        sev, _ = _classify_severity("Data loss in production database", "Customer orders unrecoverable.", [])
        assert sev == "SEV1"

    def test_sev2_elevated_latency(self):
        sev, _ = _classify_severity("Elevated latency on checkout", "Response times increased to 2400ms.", [])
        assert sev == "SEV2"

    def test_sev2_timeout(self):
        sev, _ = _classify_severity("Checkout transaction timeout", "Database queries timing out under load.", [])
        assert sev == "SEV2"

    def test_sev3_intermittent(self):
        sev, _ = _classify_severity("Intermittent errors on secondary endpoint", "Some users see occasional errors.", [])
        assert sev == "SEV3"

    def test_sev3_one_region(self):
        sev, _ = _classify_severity("One region partial slowdown", "Minority of users affected in Frankfurt.", [])
        assert sev == "SEV3"

    def test_sev4_cosmetic(self):
        sev, _ = _classify_severity("Cosmetic issue with logo alignment", "Wordmark offset by 4px in dark mode.", [])
        assert sev == "SEV4"

    def test_sev4_typo(self):
        sev, _ = _classify_severity("Typo in error message tooltip", "Tooltip text reads verfication instead of verification.", [])
        assert sev == "SEV4"

    def test_similar_incidents_escalation(self):
        similar = [{"severity": "SEV1"}, {"severity": "SEV2"}, {"severity": "SEV3"}]
        sev, reason = _classify_severity("Uncertain performance observation", "Details are unclear.", similar)
        assert sev == "SEV2"
        assert "high-severity" in reason


class TestTriageEvalDataset:
    """Validates the labelled evaluation dataset and classifier accuracy."""

    def test_triage_eval_set_exists(self):
        assert TRIAGE_EVAL_FILE.exists(), "triage_eval_set.jsonl missing"

    def test_triage_eval_set_has_required_fields(self):
        with TRIAGE_EVAL_FILE.open(encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                item = json.loads(line)
                assert "id" in item
                assert "title" in item
                assert "severity" in item
                assert item["severity"] in ("SEV1", "SEV2", "SEV3", "SEV4")

    def test_triage_scoring_runs_deterministically(self):
        """Evaluation runner scores PR #283's hint across the 25 labelled cases."""
        result = evaluate_ai4_triage(TRIAGE_EVAL_FILE)
        assert result["feature"] == "AI-4"
        assert result["total_cases"] == 25
        assert result["exact_matches"] >= 10
        assert "per_severity" in result
        for sev in ("SEV1", "SEV2", "SEV3", "SEV4"):
            assert sev in result["per_severity"]
            assert result["per_severity"][sev]["total"] > 0


# ---------------------------------------------------------------------------
# AI-2 & AI-3: Scripted Provider Unit Tests
# ---------------------------------------------------------------------------


class TestScriptedProviders:
    """Scripted providers preserved strictly as unit test fixtures."""

    @pytest.mark.skipif(sys.platform == "win32", reason="asyncio.run hangs on Windows sandbox")
    def test_scenario_evaluator_scripted_turn_1(self):
        import asyncio
        scenario = {
            "scenario_id": "SCN-01",
            "service": "payments",
            "what_it_breaks": "Slow gateway",
            "real_cause": "Gateway latency",
            "ai_first_hypothesis": "Latency bug",
        }
        provider = ScenarioEvaluatorProvider(scenario)
        resp = asyncio.run(provider.generate(messages=[], tools=[]))
        assert len(resp.tool_calls) == 3
        assert resp.tool_calls[0].tool_name == "query_log_patterns"

    @pytest.mark.skipif(sys.platform == "win32", reason="asyncio.run hangs on Windows sandbox")
    def test_drafting_adversarial_scripted(self):
        import asyncio
        provider = DraftingAdversarialProvider(kind="SUMMARY")
        resp = asyncio.run(provider.generate(messages=[], tools=[]))
        assert "INC-999" in resp.content
        assert "entry #999" in resp.content


# ---------------------------------------------------------------------------
# AI-3 Drafting: Fabricated ID Rejection Tests
# ---------------------------------------------------------------------------


class TestDraftingSanitization:
    """Test that the sanitizer strips fabricated IDs and keeps real ones."""

    def test_keeps_real_inc_ref(self):
        text = sanitize_draft_text(
            "Incident INC-42 was resolved quickly.",
            allowed_inc_refs={"INC-42"},
            allowed_inc_nums={"42"},
            allowed_entry_ids=set(),
        )
        assert "INC-42" in text

    def test_strips_fabricated_inc_ref(self):
        text = sanitize_draft_text(
            "Incident INC-999 was a major problem.",
            allowed_inc_refs={"INC-42"},
            allowed_inc_nums={"42"},
            allowed_entry_ids=set(),
        )
        assert "INC-999" not in text

    def test_keeps_real_entry_ref(self):
        text = sanitize_draft_text(
            "As noted in entry #10, the issue persisted.",
            allowed_inc_refs=set(),
            allowed_inc_nums=set(),
            allowed_entry_ids={"10"},
        )
        assert "entry #10" in text

    def test_strips_fabricated_entry_ref(self):
        text = sanitize_draft_text(
            "As noted in entry #999, the issue persisted.",
            allowed_inc_refs=set(),
            allowed_inc_nums=set(),
            allowed_entry_ids={"10"},
        )
        assert "entry #999" not in text
        assert "999" not in text


@pytest.mark.skipif(sys.platform == "win32", reason="asyncio.run hangs on Windows sandbox")
def test_draft_postmortem_structure():
    """Generate a POSTMORTEM draft and verify all six sections are populated."""
    import asyncio
    provider = MockProvider(responses=[
        ModelTurnResponse(
            content=json.dumps({
                "text": "Postmortem for INC-42.",
                "postmortem": {
                    "summary": "INC-42 caused checkout 500s.",
                    "impact": "80% of transactions affected.",
                    "rootCause": "Faulty deploy.",
                    "resolution": "Rolled back to stable.",
                    "wentWell": "Fast detection.",
                    "wentBadly": "No smoke test.",
                },
            }),
            total_tokens=50,
        ),
    ])

    req = DraftRequest(
        kind="POSTMORTEM",
        incident={"id": 42, "number": 42, "reference": "INC-42", "title": "Checkout 500", "severity": "SEV2", "status": "RESOLVED"},
        timeline=[{"id": 1, "details": "Incident created"}],
    )
    res = asyncio.run(generate_draft(request=req, provider=provider))
    assert res.kind == "POSTMORTEM"
    assert res.postmortem is not None
    pm = res.postmortem
    assert pm.summary
    assert pm.impact
    assert pm.root_cause
    assert pm.resolution
    assert pm.went_well
    assert pm.went_badly


@pytest.mark.skipif(sys.platform == "win32", reason="asyncio.run hangs on Windows sandbox")
def test_draft_rejects_fabricated_ids():
    """Adversarial provider that injects INC-999 and entry #999 must have them stripped."""
    import asyncio
    provider = MockProvider(responses=[
        ModelTurnResponse(
            content=json.dumps({
                "text": "INC-42 matches INC-999 and entry #999, confirmed by entry #1.",
            }),
            total_tokens=30,
        ),
    ])

    req = DraftRequest(
        kind="SUMMARY",
        incident={"id": 42, "number": 42, "reference": "INC-42", "title": "Gateway timeout"},
        timeline=[{"id": 1, "details": "Incident created for INC-42"}],
    )
    res = asyncio.run(generate_draft(request=req, provider=provider))
    assert "INC-42" in res.text
    assert "entry #1" in res.text
    assert "INC-999" not in res.text
    assert "entry #999" not in res.text


# ---------------------------------------------------------------------------
# AI-2 Scenario Investigation: Cause Matching Tests
# ---------------------------------------------------------------------------

class TestCauseMatching:
    """Test that check_cause_match identifies correct root causes for each scenario."""

    def test_scn01_payment_latency(self):
        assert check_cause_match("SCN-01", "Payment gateway latency degradation.")

    def test_scn02_database_deadlock(self):
        assert check_cause_match("SCN-02", "Database transaction deadlocks are causing checkout failures.")

    def test_scn03_bad_deploy(self):
        assert check_cause_match("SCN-03", "A recent checkout deployment introduced a regression.")

    def test_scn04_worker_paused(self):
        assert check_cause_match("SCN-04", "The order fulfillment worker is stalled or paused.")

    def test_scn05_cert_expiry(self):
        assert check_cause_match("SCN-05", "Payment gateway TLS certificate expiry.")

    def test_wrong_cause_rejected(self):
        assert not check_cause_match("SCN-01", "The queue worker is paused.")
