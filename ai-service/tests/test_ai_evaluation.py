"""Tests for AI evaluation runner (Issue #252, Sprint 6).

Hermetic tests using MockProvider and deterministic assertions.
No Ollama or live AI service required.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

from drafting import DraftRequest, DraftResponse, sanitize_draft_text, generate_draft
from providers import MockProvider, ModelTurnResponse, ToolCallRequest
from triage import (
    TriageRequest,
    TriageResponse,
    classify_severity_rules,
    infer_service,
    suggest_triage,
)

DATA_DIR = Path(__file__).resolve().parents[1] / "data"
TRIAGE_EVAL_FILE = DATA_DIR / "triage_eval_set.jsonl"


# ---------------------------------------------------------------------------
# AI-4 Triage: Unit Tests
# ---------------------------------------------------------------------------


class TestTriageClassifier:
    """Deterministic rule-based severity classification tests."""

    def test_sev1_total_failure(self):
        sev, reason, conf = classify_severity_rules(
            "Payment gateway total failure",
            "All payment authorizations returning 503 globally.",
        )
        assert sev == "SEV1"
        assert conf >= 0.90

    def test_sev1_data_corruption(self):
        sev, _, _ = classify_severity_rules(
            "Customer order data corruption in production",
            "Migration overwrote order keys with null values.",
        )
        assert sev == "SEV1"

    def test_sev2_latency_degradation(self):
        sev, _, _ = classify_severity_rules(
            "Payment gateway latency degradation",
            "Response times increased from 200ms to 2400ms.",
        )
        assert sev == "SEV2"

    def test_sev2_error_spike(self):
        sev, _, _ = classify_severity_rules(
            "Checkout transaction failure spike",
            "Database deadlocks causing 75% of checkout requests to fail.",
        )
        assert sev == "SEV2"

    def test_sev3_queue_backlog(self):
        sev, _, _ = classify_severity_rules(
            "Order fulfillment queue backlog",
            "Background order fulfillment worker is paused.",
        )
        assert sev == "SEV3"

    def test_sev3_delayed_email(self):
        sev, _, _ = classify_severity_rules(
            "Delayed email delivery for order receipts",
            "SendGrid SMTP rate-limit hit; emails delayed 45 minutes.",
        )
        assert sev == "SEV3"

    def test_sev4_cosmetic(self):
        sev, _, _ = classify_severity_rules(
            "Wordmark logo alignment defect on dark mode",
            "SVG wordmark offset by 4px when switching to dark theme.",
        )
        assert sev == "SEV4"

    def test_sev4_deprecation_warning(self):
        sev, _, _ = classify_severity_rules(
            "Deprecation warning in order worker log output",
            "Worker logs report Python 3.10 deprecation warnings.",
        )
        assert sev == "SEV4"

    def test_fallback_to_sev2_for_critical_service(self):
        sev, reason, conf = classify_severity_rules(
            "Something strange on payments",
            "Unknown issue detected.",
            "payments",
        )
        assert sev == "SEV2"
        assert conf < 0.90

    def test_fallback_to_sev3_for_noncritical_service(self):
        sev, _, _ = classify_severity_rules(
            "Something strange on reporting",
            "Unknown issue detected.",
            "reporting",
        )
        assert sev == "SEV3"


class TestServiceInference:
    def test_infer_payments(self):
        assert infer_service("Payment gateway timeout", "") == "payments"

    def test_infer_checkout(self):
        assert infer_service("Checkout service crash", "") == "checkout"

    def test_infer_database(self):
        assert infer_service("PostgreSQL replication lag", "") == "database"

    def test_infer_none_for_unknown(self):
        assert infer_service("Something happened", "") is None


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

    def test_triage_accuracy_above_threshold(self):
        """Classifier must achieve >= 88% accuracy on the labelled set."""
        cases = []
        with TRIAGE_EVAL_FILE.open(encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    cases.append(json.loads(line))

        correct = 0
        for item in cases:
            pred, _, _ = classify_severity_rules(
                item["title"], item.get("description", ""), item.get("service"),
            )
            if pred == item["severity"]:
                correct += 1

        accuracy = correct / len(cases)
        assert accuracy >= 0.88, f"Triage accuracy {accuracy:.1%} below 88% threshold"


@pytest.mark.skipif(sys.platform == "win32", reason="asyncio.run hangs on Windows sandbox")
def test_suggest_triage_returns_response():
    import asyncio
    req = TriageRequest(title="Payment gateway total failure", description="All payments down.")
    res = asyncio.run(suggest_triage(req, provider=None))
    assert isinstance(res, TriageResponse)
    assert res.ai_generated is True
    assert res.severity == "SEV1"
    assert res.service == "payments"
    assert len(res.reason) > 0


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
        from ai_evaluation import check_cause_match
        assert check_cause_match("SCN-01", "Payment gateway latency degradation.")

    def test_scn02_database_deadlock(self):
        from ai_evaluation import check_cause_match
        assert check_cause_match("SCN-02", "Database transaction deadlocks are causing checkout failures.")

    def test_scn03_bad_deploy(self):
        from ai_evaluation import check_cause_match
        assert check_cause_match("SCN-03", "A recent checkout deployment introduced a regression.")

    def test_scn04_worker_paused(self):
        from ai_evaluation import check_cause_match
        assert check_cause_match("SCN-04", "The order fulfillment worker is stalled or paused.")

    def test_scn05_cert_expiry(self):
        from ai_evaluation import check_cause_match
        assert check_cause_match("SCN-05", "Payment gateway TLS certificate expiry.")

    def test_wrong_cause_rejected(self):
        from ai_evaluation import check_cause_match
        assert not check_cause_match("SCN-01", "The queue worker is paused.")
