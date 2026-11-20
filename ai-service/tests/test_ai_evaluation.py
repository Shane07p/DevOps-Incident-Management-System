"""Tests for AI evaluation runner (Issue #252, Sprint 6).

Hermetic unit tests using scripted/mock providers and deterministic assertions.
No live Ollama instance required.
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import ai_evaluation
from ai_evaluation import (
    DraftingAdversarialProvider,
    ScenarioEvaluatorProvider,
    _find_invented_ids,
    _load_scenario_bundle,
    check_cause_match,
    evaluate_ai2_scenarios,
    evaluate_ai3_drafting,
    evaluate_ai4_triage,
    format_markdown_report,
)
from drafting import DraftRequest, generate_draft, sanitize_draft_text
from main import _classify_severity
from providers import MockProvider, ModelTurnResponse

DATA_DIR = Path(__file__).resolve().parents[1] / "data"
TRIAGE_EVAL_FILE = DATA_DIR / "triage_eval_set.jsonl"


# ---------------------------------------------------------------------------
# AI-4 Triage: Unit Tests (Scoring PR #283's POST /triage)
# ---------------------------------------------------------------------------

class TestTriageClassifier:
    """Deterministic rule-based severity classification tests using PR #283's classifier."""

    def test_sev1_total_outage(self):
        sev, _ = _classify_severity("Payment gateway total outage", "All payments down globally.", [])
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
    """Validates the labelled evaluation dataset and PR #283 POST /triage scoring."""

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
        """Evaluation runner scores PR #283's POST /triage across all 25 labelled cases."""
        result = evaluate_ai4_triage(TRIAGE_EVAL_FILE)
        assert result["feature"] == "AI-4"
        assert result["total_cases"] == 25
        assert result["exact_matches"] == 11
        assert "per_severity" in result
        for sev in ("SEV1", "SEV2", "SEV3", "SEV4"):
            assert sev in result["per_severity"]
            assert result["per_severity"][sev]["total"] > 0


# ---------------------------------------------------------------------------
# AI-2: Scenario Fixture Loading & Scripted End-to-End Pipeline Unit Tests
# ---------------------------------------------------------------------------

class TestScenarioFixturesAndPipeline:
    """Verifies all 5 scenario fixtures load fault logs/metrics/deploys cleanly."""

    def test_all_five_scenario_bundles_load_fault_signals(self):
        expected_log_substr = {
            "SCN-01": "payment.gateway.slow",
            "SCN-02": "checkout.transaction_failed",
            "SCN-03": "checkout.null_pointer_exception",
            "SCN-04": "queue.paused",
            "SCN-05": "ssl.cert_expiring",
        }
        for sid, needle in expected_log_substr.items():
            bundle, fault_started_at = _load_scenario_bundle(sid, "payments", "Fault test")
            assert fault_started_at
            assert len(bundle.logs) > 0, f"{sid} loaded no logs"
            assert any(needle in (log.get("message") or "") for log in bundle.logs), (
                f"{sid} missing expected fault log '{needle}'"
            )
            assert len(bundle.metrics) > 0, f"{sid} loaded no metrics"
            for m in bundle.metrics:
                assert len(m["points"]) == 2, f"{sid} metric {m['metric']} should have baseline + fault points"
            if sid == "SCN-03":
                assert len(bundle.deploys) == 1
                assert bundle.deploys[0]["version"] == "v1.0.1-broken"

    def test_scenario_evaluator_scripted_turn_1(self):
        scenario = {
            "scenario_id": "SCN-01",
            "service": "payments",
            "what_it_breaks": "Slow gateway",
            "real_cause": "Gateway latency",
            "ai_first_hypothesis": "Payment gateway latency degradation.",
        }
        provider = ScenarioEvaluatorProvider(scenario)
        resp = asyncio.run(provider.generate(messages=[], tools=[]))
        assert len(resp.tool_calls) == 3
        assert resp.tool_calls[0].tool_name == "query_log_patterns"

    def test_ai2_evaluation_pipeline_with_scripted_provider(self):
        """Hermetic end-to-end check of evaluate_ai2_scenarios using ScenarioEvaluatorProvider."""
        result = asyncio.run(evaluate_ai2_scenarios(use_mock=True, timeout_seconds=10.0))
        assert result["feature"] == "AI-2"
        assert result["provider"] == "mock"
        assert result["total_scenarios"] == 5
        assert result["top_1_matches"] == 5
        for sc in result["scenarios"]:
            assert sc["status"] == "completed"
            assert sc["top_1_match"] is True
            assert sc["evidence_citations"] >= 1


# ---------------------------------------------------------------------------
# AI-3 Drafting: Sanitization, Regex Detection & Scripted Pipeline Tests
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

    def test_find_invented_ids_catches_arbitrary_hallucinations(self):
        req = DraftRequest(
            kind="SUMMARY",
            incident={"id": 42, "number": 42, "reference": "INC-42", "title": "Gateway timeout"},
            timeline=[{"id": 1, "details": "Created"}],
        )
        leaked = _find_invented_ids("See INC-42, INC-123, incident #55, entry #1, and entry #88.", req)
        assert "INC-123" in leaked
        assert "incident #55" in leaked
        assert "entry #88" in leaked
        assert "INC-42" not in leaked
        assert "entry #1" not in leaked


def test_drafting_adversarial_scripted():
    provider = DraftingAdversarialProvider(kind="SUMMARY")
    resp = asyncio.run(provider.generate(messages=[], tools=[]))
    assert "INC-999" in resp.content
    assert "entry #999" in resp.content


def test_ai3_evaluation_pipeline_with_scripted_provider():
    """Hermetic end-to-end check of evaluate_ai3_drafting using DraftingAdversarialProvider."""
    result = asyncio.run(evaluate_ai3_drafting(use_mock=True))
    assert result["feature"] == "AI-3"
    assert result["provider"] == "mock"
    assert result["total_draft_evaluations"] == 3
    assert result["checklist_passed_count"] == 3
    assert result["fabricated_id_leak_rate"] == 0.0
    assert result["mean_latency_ms"] >= 0.0


def test_ai3_evaluation_uses_current_drafting_context():
    payloads = []

    class RecordingProvider(DraftingAdversarialProvider):
        async def generate(self, messages, tools, **kwargs):
            assert "unconfirmed AI hypotheses" in messages[0]["content"]
            payloads.append(json.loads(messages[1]["content"]))
            return await super().generate(messages, tools, **kwargs)

    result = asyncio.run(evaluate_ai3_drafting(provider_factory=RecordingProvider))

    assert result["provider"] == "custom"
    assert [payload["kind"] for payload in payloads] == ["SUMMARY", "STATUS_UPDATE", "POSTMORTEM"]
    for payload in payloads:
        assert "acceptedHypotheses" not in payload
        assert set(payload["hypotheses"][0]) == {"rank", "title", "confidence"}
        assert set(payload["followUps"][0]) == {"title", "kind", "owner", "dueDate", "status"}
        assert payload["acceptedActions"]


def test_draft_postmortem_structure():
    """Generate a POSTMORTEM draft and verify all six sections are populated."""
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


def test_draft_rejects_fabricated_ids():
    """Adversarial provider that injects INC-999 and entry #999 must have them stripped."""
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
# AI-2 Cause Matching & Markdown Formatter Tests
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


def test_format_markdown_report_handles_partial_results():
    ai4 = evaluate_ai4_triage(TRIAGE_EVAL_FILE)
    md = format_markdown_report({"ai4": ai4}, "qwen2.5:0.5b", "abc1234", "2026-11-19")
    assert "AI-4: Triage Severity Classification" in md
    assert "11/25" in md
    assert "abc1234" in md
    assert "No live Ollama result is recorded" in md
    assert "Model evaluated:" not in md


def test_scripted_ai2_report_does_not_claim_live_model_accuracy():
    ai2 = asyncio.run(evaluate_ai2_scenarios(use_mock=True, timeout_seconds=10.0))
    md = format_markdown_report({"ai2": ai2}, "qwen2.5:0.5b", "abc1234", "2026-11-20")

    assert "ScenarioEvaluatorProvider" in md
    assert "Scripted cause-match check" in md
    assert "5/5" in md
    assert "No live Ollama result is recorded" in md
    assert "Top-1 Cause Accuracy" not in md
    assert "were run against live" not in md


def test_scripted_ai3_report_identifies_the_adversarial_provider():
    ai3 = asyncio.run(evaluate_ai3_drafting(use_mock=True))
    md = format_markdown_report({"ai3": ai3}, "qwen2.5:0.5b", "abc1234", "2026-11-20")

    assert "scripted DraftingAdversarialProvider" in md
    assert "3/3" in md
    assert "No live Ollama result is recorded" in md
    assert "against `OllamaProvider`" not in md


def test_scripted_cli_does_not_call_cause_matching_model_accuracy(monkeypatch, capsys):
    monkeypatch.setattr("sys.argv", ["ai_evaluation.py", "--eval", "ai2", "--use-mock"])
    ai_evaluation.main()
    output = capsys.readouterr().out

    assert "ScenarioEvaluatorProvider" in output
    assert "5/5" in output
    assert "accuracy" not in output.lower()
