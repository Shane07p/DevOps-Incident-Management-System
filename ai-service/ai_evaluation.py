"""
ai_evaluation.py - Evaluation Suite for AI Features (Issue #252, Sprint 6).
----------------------------------------------------------------------------
Implements evaluation runners for:
  1. AI-2 (AI SRE on demo scenarios SCN-01 to SCN-05):
     - Top-1 cause identification accuracy
     - End-to-end investigation duration (latency in ms)
     - Evidence citation grounding
  2. AI-3 (AI Drafting):
     - Checklist evaluation across SUMMARY, STATUS_UPDATE, POSTMORTEM
     - Real incident reference retention
     - Zero hallucinated/fabricated ID leakage (regex-verified against allowed refs)
     - Valid structural schema adherence
     - Draft pipeline duration (ms)
  3. AI-4 (Triage hint):
     - Severity classification accuracy of PR #283's POST /triage endpoint
       against the 25 labelled test cases in data/triage_eval_set.jsonl
     - Per-severity accuracy breakdown

Usage:
  python ai_evaluation.py                                  # Run all evals with live Ollama
  python ai_evaluation.py --eval ai2                       # Run AI-2 evaluation only
  python ai_evaluation.py --eval ai3                       # Run AI-3 evaluation only
  python ai_evaluation.py --eval ai4                       # Run AI-4 evaluation only
  python ai_evaluation.py --use-mock                       # Scripted pipeline checks; no live model
  python ai_evaluation.py --markdown --save-json out.json  # Output Markdown and save raw JSON
"""

from __future__ import annotations

import argparse
import asyncio
import datetime
import json
import logging
import subprocess
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import yaml
from fastapi.testclient import TestClient

from agent_loop import (
    AgentInvestigationLoop,
    AgentLimits,
    InvestigationRequest,
    InvestigationResult,
)
from agent_tools import EvidenceBundle, build_bundle_tool_registry
from drafting import (
    DraftRequest,
    DraftResponse,
    _ENTRY_REF_RE,
    _INCIDENT_NUM_RE,
    _INC_REF_RE,
    _allowed_refs,
    generate_draft,
)
from main import app, get_agent_model, get_ollama_url
from providers import LlmProvider, ModelTurnResponse, OllamaProvider, ToolCallRequest

logger = logging.getLogger("ai-service.evaluation")

DATA_DIR = Path(__file__).resolve().parent / "data"
SCENARIOS_FILE = DATA_DIR / "scenarios.yaml"
FIXTURES_DIR = DATA_DIR / "fixtures" / "scenarios"
TRIAGE_EVAL_FILE = DATA_DIR / "triage_eval_set.jsonl"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _detect_git_commit() -> str:
    """Return the short HEAD commit SHA, or 'unknown' if git is unavailable."""
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=Path(__file__).resolve().parent,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        return out or "unknown"
    except Exception:
        return "unknown"


def _shift_iso_timestamp(iso_ts: str, delta_seconds: float) -> str:
    """Shift an ISO-8601 timestamp by delta_seconds; fall back to iso_ts on parse error."""
    try:
        dt = datetime.datetime.fromisoformat(iso_ts.replace("Z", "+00:00"))
        shifted = dt + datetime.timedelta(seconds=delta_seconds)
        return shifted.isoformat()
    except Exception:
        return iso_ts


def _extract_numeric_metric(val: Any) -> Optional[float]:
    """Extract a representative numeric value from a scenario metrics.json entry."""
    if isinstance(val, (int, float)) and not isinstance(val, bool):
        return float(val)
    if isinstance(val, dict):
        for key in ("p99", "error_rate", "p50", "value", "max", "avg", "success_rate"):
            candidate = val.get(key)
            if isinstance(candidate, (int, float)) and not isinstance(candidate, bool):
                return float(candidate)
    return None


def _format_log_message(item: Dict[str, Any]) -> str:
    """Build a stable, aggregatable log message preserving fault event and error/message text."""
    event = str(item.get("event") or "").strip()
    details = item.get("details")
    detail_text = ""
    if isinstance(details, dict):
        for key in ("error", "message"):
            v = details.get(key)
            if isinstance(v, str) and v.strip():
                detail_text = v.strip()
                break
        if not detail_text and event == "deploy.event":
            ver = details.get("version")
            svc = details.get("service")
            if ver:
                detail_text = f"Deployed {ver} to {svc or 'service'}"
    if event and detail_text:
        return f"{event}: {detail_text}"
    return event or detail_text


# ---------------------------------------------------------------------------
# AI-2: Scripted Unit-Test Provider (ONLY for --use-mock / unit tests)
# ---------------------------------------------------------------------------

class ScenarioEvaluatorProvider(LlmProvider):
    """
    Deterministic scripted provider for hermetic unit tests ONLY.
    Never present these scripted checks as live model metrics; live runs use OllamaProvider.
    """

    def __init__(self, scenario: Dict[str, Any]):
        self.scenario = scenario
        self.turn = 0

    async def generate(
        self,
        messages: List[Dict[str, Any]],
        tools: List[Dict[str, Any]],
        timeout: float = 10.0,
        max_output_tokens: Optional[int] = None,
    ) -> ModelTurnResponse:
        self.turn += 1
        service = self.scenario["service"]
        sid = self.scenario["scenario_id"]

        if self.turn == 1:
            tool_calls = [
                ToolCallRequest(
                    call_id="call_logs",
                    tool_name="query_log_patterns",
                    arguments={"service": service},
                ),
                ToolCallRequest(
                    call_id="call_deploys",
                    tool_name="query_deploy_events",
                    arguments={"service": service},
                ),
                ToolCallRequest(
                    call_id="call_metrics",
                    tool_name="query_metric_anomalies",
                    arguments={"service": service},
                ),
            ]
            return ModelTurnResponse(
                content="Investigating incident logs, deploys, and metrics.",
                tool_calls=tool_calls,
                total_tokens=180,
            )

        evidence_ids: List[str] = []
        for msg in messages:
            if msg.get("role") == "tool":
                try:
                    payload = json.loads(msg.get("content", "{}"))
                    for ev in payload.get("evidence", []):
                        if isinstance(ev, dict) and "id" in ev:
                            evidence_ids.append(str(ev["id"]))
                except Exception:
                    pass

        primary_ev = evidence_ids[:2] if evidence_ids else []
        hyp_text = self.scenario["ai_first_hypothesis"]
        real_cause = self.scenario["real_cause"]

        final_payload = {
            "summary": f"Investigation completed for {sid}. Root cause identified.",
            "hypotheses": [
                {
                    "hypothesis": hyp_text,
                    "confidence": 0.95,
                    "evidenceRefs": primary_ev,
                    "reasoning": f"Observed diagnostic signals confirm {real_cause}.",
                }
            ],
            "suggestedActions": [
                f"Mitigate {sid} fault on {service} by restoring service configuration or rolling back deployment."
            ],
        }
        return ModelTurnResponse(
            content=json.dumps(final_payload),
            tool_calls=[],
            total_tokens=220,
        )


def _load_scenario_bundle(
    scenario_id: str,
    service: str,
    what_it_breaks: str,
    fixtures_dir: Optional[Path] = None,
) -> Tuple[EvidenceBundle, str]:
    """
    Load recorded Demo Shop fixtures for a scenario into an EvidenceBundle.
    Returns (bundle, fault_started_at).
    """
    base_dir = fixtures_dir or FIXTURES_DIR
    scen_dir = base_dir / scenario_id
    logs_file = scen_dir / "logs.jsonl"
    metrics_file = scen_dir / "metrics.json"
    deploys_file = scen_dir / "deploy_events.json"
    metadata_file = scen_dir / "metadata.json"

    meta: Dict[str, Any] = {}
    if metadata_file.exists():
        with metadata_file.open(encoding="utf-8") as f:
            meta = json.load(f)

    fault_started_at = str(meta.get("fault_started_at") or "2026-11-11T10:00:00+00:00")
    baseline_ts = _shift_iso_timestamp(fault_started_at, -300.0)

    # Load fault-window logs (at or after fault_started_at), prioritizing ERROR/WARN entries
    fault_logs: List[Dict[str, Any]] = []
    all_logs: List[Dict[str, Any]] = []
    if logs_file.exists():
        with logs_file.open(encoding="utf-8") as f:
            for idx, line in enumerate(f):
                if not line.strip():
                    continue
                item = json.loads(line)
                ts = str(item.get("timestamp") or fault_started_at)
                entry = {
                    "id": str(idx + 1),
                    "timestamp": ts,
                    "level": str(item.get("level") or "INFO").upper(),
                    "message": _format_log_message(item),
                    "attributes": item.get("details") if isinstance(item.get("details"), dict) else {},
                }
                all_logs.append(entry)
                if ts >= fault_started_at:
                    fault_logs.append(entry)

    selected_logs = fault_logs if fault_logs else all_logs
    # Put ERROR/WARN logs first so fault signals are never truncated
    selected_logs.sort(key=lambda e: (0 if e["level"] in ("ERROR", "WARN", "WARNING") else 1, e["timestamp"]))
    selected_logs = selected_logs[:100]

    # Load baseline + fault metrics handling both dict and scalar numeric entries
    metrics_list: List[Dict[str, Any]] = []
    if metrics_file.exists():
        with metrics_file.open(encoding="utf-8") as f:
            m_data = json.load(f)
        baseline_metrics = m_data.get("baseline", {}) if isinstance(m_data, dict) else {}
        fault_metrics = m_data.get("fault", {}) if isinstance(m_data, dict) else {}
        for m_name, fault_val in fault_metrics.items():
            points: List[Dict[str, Any]] = []
            base_num = _extract_numeric_metric(baseline_metrics.get(m_name))
            fault_num = _extract_numeric_metric(fault_val)
            if base_num is not None:
                points.append({"timestamp": baseline_ts, "value": base_num})
            if fault_num is not None:
                points.append({"timestamp": fault_started_at, "value": fault_num})
            if points:
                metrics_list.append({
                    "metric": m_name,
                    "serviceName": service,
                    "points": points,
                })

    # Load deploy events
    deploys_list: List[Dict[str, Any]] = []
    if deploys_file.exists():
        with deploys_file.open(encoding="utf-8") as f:
            d_data = json.load(f)
        if isinstance(d_data, list):
            for idx, d in enumerate(d_data):
                if not isinstance(d, dict):
                    continue
                deploys_list.append({
                    "id": idx + 1,
                    "service": d.get("service", service),
                    "version": d.get("version", "v1.0.1"),
                    "commit": d.get("commit", "abc1234"),
                    "author": d.get("author", "ci-bot"),
                    "deployedAt": d.get("timestamp") or d.get("deployed_at") or d.get("deployedAt") or fault_started_at,
                })

    bundle = EvidenceBundle(
        incident={
            "id": int("".join(filter(str.isdigit, scenario_id)) or "1"),
            "reference": f"INC-{scenario_id}",
            "title": what_it_breaks,
            "description": f"Investigating anomaly on {service}: {what_it_breaks}",
            "service": service,
            "severity": "SEV1",
            "status": "OPEN",
        },
        timeline=[
            {
                "id": 1,
                "eventType": "INCIDENT_CREATED",
                "actor": "monitoring-bot",
                "details": f"Alert triggered on {service}: {what_it_breaks}",
                "recordedAt": fault_started_at,
            }
        ],
        deploys=deploys_list,
        logs=selected_logs,
        metrics=metrics_list,
    )
    return bundle, fault_started_at


def check_cause_match(scenario_id: str, hypothesis_text: str) -> bool:
    """Check whether hypothesis text accurately identifies the scenario's real root cause."""
    text = hypothesis_text.lower()
    if scenario_id == "SCN-01":
        return any(k in text for k in ("latency", "slow", "delay", "2-second", "2000ms", "timeout")) and "payment" in text
    if scenario_id == "SCN-02":
        return any(k in text for k in ("deadlock", "database", "transaction")) or "checkout failure" in text
    if scenario_id == "SCN-03":
        return any(k in text for k in ("deploy", "regression", "v1.0.1-broken", "nullpointerexception", "null pointer"))
    if scenario_id == "SCN-04":
        return any(k in text for k in ("paused", "stalled", "backlog", "queue", "worker"))
    if scenario_id == "SCN-05":
        return any(k in text for k in ("certificate", "cert", "tls", "ssl", "expir"))
    return False


async def evaluate_ai2_scenarios(
    scenarios_path: Optional[Path] = None,
    fixtures_dir: Optional[Path] = None,
    provider_override: Optional[LlmProvider] = None,
    use_mock: bool = False,
    model: Optional[str] = None,
    timeout_seconds: Optional[float] = None,
) -> Dict[str, Any]:
    """
    Evaluate AI-2 AI SRE on demo scenarios (SCN-01 to SCN-05).
    Computes cause matches and end-to-end investigation duration.
    Uses real OllamaProvider by default; ScenarioEvaluatorProvider is for unit tests only.
    """
    path = scenarios_path or SCENARIOS_FILE
    with path.open(encoding="utf-8") as f:
        data = yaml.safe_load(f)
    scenarios = data.get("scenarios", [])

    eval_model = model or get_agent_model()
    env_limits = AgentLimits.from_env()
    effective_timeout = timeout_seconds if timeout_seconds is not None else env_limits.timeout_seconds

    results: List[Dict[str, Any]] = []
    top_1_matches = 0
    latencies_ms: List[float] = []

    for sc in scenarios:
        sid = sc["scenario_id"]
        service = sc["service"]
        what_it_breaks = sc["what_it_breaks"]
        real_cause = sc["real_cause"]
        expected_hyp = sc["ai_first_hypothesis"]

        bundle, fault_started_at = _load_scenario_bundle(
            sid, service, what_it_breaks, fixtures_dir=fixtures_dir
        )
        registry = build_bundle_tool_registry(bundle)
        if provider_override is not None:
            provider = provider_override
        elif use_mock:
            provider = ScenarioEvaluatorProvider(sc)
        else:
            provider = OllamaProvider(base_url=get_ollama_url(), model=eval_model)

        loop = AgentInvestigationLoop(
            provider=provider,
            tool_registry=registry,
            default_limits=AgentLimits.create_validated(
                max_steps=env_limits.max_steps,
                timeout_seconds=effective_timeout,
                max_tokens=env_limits.max_tokens,
            ),
        )

        t_start = time.perf_counter()
        inv_res: InvestigationResult = await loop.run(
            request=InvestigationRequest(
                service=service,
                incident_start=fault_started_at,
                description=what_it_breaks,
                evidence_bundle=bundle,
            )
        )
        duration_ms = (time.perf_counter() - t_start) * 1000.0
        latencies_ms.append(duration_ms)

        has_hyp = len(inv_res.hypotheses) > 0
        top_hyp = inv_res.hypotheses[0].hypothesis if has_hyp else ""
        top_confidence = inv_res.hypotheses[0].confidence if has_hyp else 0.0
        citations_count = len(inv_res.hypotheses[0].evidence_refs) if has_hyp else 0

        is_match = check_cause_match(sid, top_hyp) if has_hyp else False
        if is_match:
            top_1_matches += 1

        results.append({
            "scenario_id": sid,
            "service": service,
            "switch": sc["switch"],
            "real_cause": real_cause,
            "expected_hypothesis": expected_hyp,
            "generated_top_hypothesis": top_hyp,
            "top_1_match": is_match,
            "confidence": top_confidence,
            "evidence_citations": citations_count,
            "steps_taken": inv_res.steps_taken,
            "tokens_used": inv_res.tokens_used,
            "duration_ms": round(duration_ms, 2),
            "status": inv_res.status,
            "summary": inv_res.summary,
        })

    total_scenarios = len(scenarios)
    top_1_acc = top_1_matches / total_scenarios if total_scenarios > 0 else 0.0
    mean_duration_ms = sum(latencies_ms) / len(latencies_ms) if latencies_ms else 0.0

    return {
        "feature": "AI-2",
        "model": eval_model,
        "provider": "custom" if provider_override is not None else "mock" if use_mock else "ollama",
        "timeout_seconds": effective_timeout,
        "total_scenarios": total_scenarios,
        "top_1_matches": top_1_matches,
        "top_1_accuracy": round(top_1_acc, 4),
        "mean_time_to_first_hypothesis_ms": round(mean_duration_ms, 2),
        "min_time_ms": round(min(latencies_ms), 2) if latencies_ms else 0.0,
        "max_time_ms": round(max(latencies_ms), 2) if latencies_ms else 0.0,
        "scenarios": results,
    }


# ---------------------------------------------------------------------------
# AI-3: Drafting Checklist Evaluation
# ---------------------------------------------------------------------------

class DraftingAdversarialProvider(LlmProvider):
    """
    Adversarial mock provider for hermetic unit tests ONLY.
    Injects fabricated IDs to verify the sanitization filter strips them.
    """

    def __init__(self, kind: str):
        self.kind = kind

    async def generate(
        self,
        messages: List[Dict[str, Any]],
        tools: List[Dict[str, Any]],
        timeout: float = 10.0,
        max_output_tokens: Optional[int] = None,
    ) -> ModelTurnResponse:
        if self.kind == "SUMMARY":
            content = json.dumps({
                "text": "Incident INC-42 occurred on Payments. We also saw hallucinated INC-999 and entry #999.",
            })
        elif self.kind == "STATUS_UPDATE":
            content = json.dumps({
                "text": "Status update for INC-42: Service degraded. Fabricated incident #777 and entry #404 are not real.",
            })
        else:  # POSTMORTEM
            content = json.dumps({
                "text": "Postmortem for INC-42",
                "postmortem": {
                    "summary": "Payments experienced delay in INC-42, not INC-999.",
                    "impact": "15% of checkout transactions delayed according to entry #1.",
                    "rootCause": "Timeout bug as described in entry #999 and INC-777.",
                    "resolution": "Rollback completed for INC-42.",
                    "wentWell": "Quick acknowledgment at entry #1.",
                    "wentBadly": "False alerts from unknown INC-999.",
                },
            })
        return ModelTurnResponse(content=content, total_tokens=150)


def _find_invented_ids(text: str, request: DraftRequest) -> List[str]:
    """Return any incident or timeline entry references in text that are not in request."""
    allowed_inc_refs, allowed_inc_nums, allowed_entry_ids = _allowed_refs(request)
    invented: List[str] = []

    for m in _INC_REF_RE.finditer(text):
        normalized = f"INC-{m.group(1)}"
        if normalized not in allowed_inc_refs:
            invented.append(m.group(0))

    for m in _INCIDENT_NUM_RE.finditer(text):
        if m.group(1) not in allowed_inc_nums:
            invented.append(m.group(0))

    for m in _ENTRY_REF_RE.finditer(text):
        if m.group(1) not in allowed_entry_ids:
            invented.append(m.group(0))

    return invented


async def evaluate_ai3_drafting(
    provider_factory: Optional[Any] = None,
    use_mock: bool = False,
    model: Optional[str] = None,
    timeout_seconds: float = 60.0,
) -> Dict[str, Any]:
    """
    Evaluate AI-3 Drafting checklist per draft kind:
      1. Cites real incidents/timeline entries
      2. Invents no IDs (regex-checked against allowed incident & entry IDs)
      3. Valid structure adhering to DraftResponse / PostmortemDraft
    Uses real OllamaProvider by default; DraftingAdversarialProvider is for unit tests only.
    """
    sample_incident = {
        "id": 42,
        "number": 42,
        "reference": "INC-42",
        "title": "Payment gateway latency elevated",
        "description": "Payments timing out under high load",
        "service": "payments",
        "severity": "SEV2",
        "status": "RESOLVED",
    }
    sample_timeline = [
        {"id": 1, "details": "Incident created for INC-42"},
        {"id": 2, "details": "Rollback initiated"},
    ]

    kinds = ["SUMMARY", "STATUS_UPDATE", "POSTMORTEM"]
    checklist_results: List[Dict[str, Any]] = []
    latencies_ms: List[float] = []
    eval_model = model or get_agent_model()

    for kind in kinds:
        if provider_factory is not None:
            provider = provider_factory(kind)
        elif use_mock:
            provider = DraftingAdversarialProvider(kind)
        else:
            provider = OllamaProvider(base_url=get_ollama_url(), model=eval_model)

        req = DraftRequest(
            kind=kind,  # type: ignore[arg-type]
            incident=sample_incident,
            timeline=sample_timeline,
            hypotheses=[{"rank": 1, "title": "Gateway timeout", "confidence": 0.7}],
            follow_ups=[{
                "title": "Upgrade HTTP client pool",
                "kind": "PREVENTIVE",
                "owner": "Evaluation engineer",
                "dueDate": "2026-12-01",
                "status": "OPEN",
            }],
            accepted_actions=["Inspect the gateway connection pool"],
        )

        t_start = time.perf_counter()
        error_msg: Optional[str] = None
        try:
            res: Optional[DraftResponse] = await generate_draft(
                request=req, provider=provider, timeout=timeout_seconds
            )
        except Exception as exc:
            res = None
            error_msg = str(exc)
        duration_ms = (time.perf_counter() - t_start) * 1000.0
        latencies_ms.append(duration_ms)

        if res is None:
            checklist_results.append({
                "kind": kind,
                "cites_real_incidents": False,
                "invents_no_ids": True,
                "leaked_fabricated_ids": [],
                "valid_structure": False,
                "checklist_passed": False,
                "duration_ms": round(duration_ms, 2),
                "error": error_msg,
                "text_sample": "",
            })
            continue

        # 1. Cites real incidents check
        full_text = res.text
        if res.postmortem:
            pm = res.postmortem
            full_text += f" {pm.summary} {pm.impact} {pm.root_cause} {pm.resolution} {pm.went_well} {pm.went_badly}"

        cites_real = "INC-42" in full_text or "entry #1" in full_text.lower() or "entry #2" in full_text.lower()

        # 2. Invents no IDs check (any incident/entry ID not in request)
        leaked_fabricated = _find_invented_ids(full_text, req)
        invents_no_ids = len(leaked_fabricated) == 0

        # 3. Valid structure check
        valid_structure = False
        if kind in ("SUMMARY", "STATUS_UPDATE"):
            valid_structure = bool(res.text and len(res.text.strip()) > 10 and res.postmortem is None)
        elif kind == "POSTMORTEM":
            valid_structure = bool(
                res.postmortem is not None
                and res.postmortem.summary
                and res.postmortem.impact
                and res.postmortem.root_cause
                and res.postmortem.resolution
                and res.postmortem.went_well
                and res.postmortem.went_badly
            )

        passed_all = cites_real and invents_no_ids and valid_structure

        checklist_results.append({
            "kind": kind,
            "cites_real_incidents": cites_real,
            "invents_no_ids": invents_no_ids,
            "leaked_fabricated_ids": leaked_fabricated,
            "valid_structure": valid_structure,
            "checklist_passed": passed_all,
            "duration_ms": round(duration_ms, 2),
            "text_sample": res.text[:100] + "..." if len(res.text) > 100 else res.text,
        })

    total = len(checklist_results)
    passed_count = sum(1 for r in checklist_results if r["checklist_passed"])
    leaked_count = sum(1 for r in checklist_results if not r["invents_no_ids"])
    mean_latency_ms = sum(latencies_ms) / len(latencies_ms) if latencies_ms else 0.0

    return {
        "feature": "AI-3",
        "model": eval_model,
        "provider": "custom" if provider_factory is not None else "mock" if use_mock else "ollama",
        "total_draft_evaluations": total,
        "checklist_passed_count": passed_count,
        "checklist_pass_rate": round(passed_count / total, 4) if total else 0.0,
        "fabricated_id_leak_rate": round(leaked_count / total, 4) if total else 0.0,
        "mean_latency_ms": round(mean_latency_ms, 2),
        "min_latency_ms": round(min(latencies_ms), 2) if latencies_ms else 0.0,
        "max_latency_ms": round(max(latencies_ms), 2) if latencies_ms else 0.0,
        "drafts": checklist_results,
    }


# ---------------------------------------------------------------------------
# AI-4: Triage Severity Classification Evaluation (PR #283 POST /triage)
# ---------------------------------------------------------------------------

def evaluate_ai4_triage(
    eval_set_path: Optional[Path] = None,
) -> Dict[str, Any]:
    """
    Evaluate PR #283's AI-4 triage hint endpoint (POST /triage, called by TriageHintService)
    against the labelled test cases in data/triage_eval_set.jsonl.
    """
    path = eval_set_path or TRIAGE_EVAL_FILE
    if not path.exists():
        raise FileNotFoundError(f"Triage evaluation dataset not found: {path}")

    cases: List[Dict[str, Any]] = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            if line.strip():
                cases.append(json.loads(line))

    client = TestClient(app)
    total = len(cases)
    exact_matches = 0

    per_severity: Dict[str, Dict[str, int]] = {
        "SEV1": {"total": 0, "correct": 0},
        "SEV2": {"total": 0, "correct": 0},
        "SEV3": {"total": 0, "correct": 0},
        "SEV4": {"total": 0, "correct": 0},
    }

    eval_rows: List[Dict[str, Any]] = []

    for item in cases:
        cid = item["id"]
        title = item["title"]
        desc = item.get("description", "")
        svc = item.get("service")
        expected_sev = item["severity"]

        resp = client.post(
            "/triage",
            json={
                "title": title,
                "description": desc,
                "similar_incidents": [],
                "services": [],
            },
        )
        resp.raise_for_status()
        body = resp.json()
        pred_sev = body["severity"]
        reason = body["reason"]
        is_match = pred_sev == expected_sev

        if is_match:
            exact_matches += 1

        if expected_sev in per_severity:
            per_severity[expected_sev]["total"] += 1
            if is_match:
                per_severity[expected_sev]["correct"] += 1

        eval_rows.append({
            "id": cid,
            "title": title,
            "service": svc,
            "expected_severity": expected_sev,
            "suggested_severity": pred_sev,
            "match": is_match,
            "reason": reason,
        })

    accuracy = exact_matches / total if total > 0 else 0.0

    severity_stats = {}
    for sev, counts in per_severity.items():
        stot = counts["total"]
        scorr = counts["correct"]
        severity_stats[sev] = {
            "total": stot,
            "correct": scorr,
            "accuracy": round(scorr / stot, 4) if stot > 0 else 0.0,
        }

    return {
        "feature": "AI-4",
        "endpoint": "POST /triage (PR #283 / TriageHintService)",
        "total_cases": total,
        "exact_matches": exact_matches,
        "accuracy": round(accuracy, 4),
        "per_severity": severity_stats,
        "cases": eval_rows,
    }


# ---------------------------------------------------------------------------
# Aggregated Evaluation Runner & Report Formatter
# ---------------------------------------------------------------------------

async def run_all_evaluations(
    use_mock: bool = False,
    model: Optional[str] = None,
    timeout_seconds: Optional[float] = None,
) -> Dict[str, Any]:
    ai2_res = await evaluate_ai2_scenarios(
        use_mock=use_mock, model=model, timeout_seconds=timeout_seconds
    )
    ai3_res = await evaluate_ai3_drafting(
        use_mock=use_mock,
        model=model,
        timeout_seconds=timeout_seconds if timeout_seconds is not None else 60.0,
    )
    ai4_res = evaluate_ai4_triage()

    return {
        "evaluated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "ai2": ai2_res,
        "ai3": ai3_res,
        "ai4": ai4_res,
    }


def _provider_description(result: Dict[str, Any], scripted_provider: str) -> str:
    if result.get("provider") == "ollama":
        return "live OllamaProvider"
    if result.get("provider") == "mock":
        return f"scripted {scripted_provider}"
    return "custom provider (not classified as a live model run)"


def format_markdown_report(
    results: Dict[str, Any],
    model_name: str,
    commit_sha: str,
    date_str: str,
) -> str:
    lines = [
        "## Sprint 6 — AI Evaluation Runs (Issue #252)",
        "",
        f"Evaluated on {date_str}, commit `{commit_sha}` (branch `feat/ai-evaluation-runs`), across features AI-2 (SRE scenario investigation), AI-3 (drafting verification), and AI-4 (severity triage hint).",
    ]
    has_live_result = any(
        results.get(feature, {}).get("provider") == "ollama" for feature in ("ai2", "ai3")
    )
    if has_live_result:
        lines.append(f"Live model for sections marked live: `{model_name}` via `OllamaProvider`.")
    else:
        lines.extend([
            f"Configured model: `{model_name}` (configuration only; see each section's provider).",
            "No live Ollama result is recorded in this report.",
        ])

    if "ai2" in results:
        ai2 = results["ai2"]
        source = _provider_description(ai2, "ScenarioEvaluatorProvider")
        cause_metric = (
            "Top-1 Cause Accuracy" if ai2.get("provider") == "ollama"
            else "Scripted cause-match check" if ai2.get("provider") == "mock"
            else "Cause-match check"
        )
        lines.extend([
            "",
            "### AI-2: AI SRE Scenario Investigation",
            "",
            "Evaluation across the 5 demo fault scenarios (`SCN-01` to `SCN-05`) from `ai-service/data/scenarios.yaml` and their recorded fixtures in `ai-service/data/fixtures/scenarios/`.",
            f"All results and timings in this section come from {source}.",
            "",
            f"- **{cause_metric}:** {ai2['top_1_matches']}/{ai2['total_scenarios']} on {ai2['total_scenarios']} demo scenarios ({ai2['top_1_accuracy']:.1%})",
            f"- **End-to-end investigation duration ({source}):** mean {ai2['mean_time_to_first_hypothesis_ms']:.1f} ms (min: {ai2['min_time_ms']:.1f} ms, max: {ai2['max_time_ms']:.1f} ms). The timer wraps the complete agent loop, not a streaming first-hypothesis event.",
            "",
            "| Scenario | Service | Fault Switch | Cause Matches Scenario Label | Generated Top-1 Hypothesis | Time (ms) | Status |",
            "|---|---|---|---|---|---|---|",
        ])
        for s in ai2["scenarios"]:
            match_str = "Passed" if s["top_1_match"] else "Failed"
            hyp_clean = (s["generated_top_hypothesis"] or "*(none)*").replace("|", "\\|").replace("\n", " ")
            lines.append(
                f"| `{s['scenario_id']}` | {s['service']} | `{s['switch']}` | **{match_str}** | {hyp_clean} | {s['duration_ms']:.1f} | `{s['status']}` |"
            )
        if ai2.get("provider") == "mock":
            lines.extend([
                "",
                "ScenarioEvaluatorProvider reads the expected hypotheses from scenarios.yaml. These are scripted pipeline regression checks, not measurements of model accuracy or inference latency.",
            ])
        else:
            lines.extend(["", "Demo fixtures are a small sample and do not establish production accuracy."])

    if "ai3" in results:
        ai3 = results["ai3"]
        source = _provider_description(ai3, "DraftingAdversarialProvider")
        lines.extend([
            "",
            "### AI-3: Drafting Verification Checklist",
            "",
            f"Evaluation across all writing kinds (`SUMMARY`, `STATUS_UPDATE`, `POSTMORTEM`) using {source} with reference sanitization and schema verification. All results and timings below come from that provider.",
            "Input is a synthetic INC-42 incident with timeline entries 1 and 2, an unconfirmed hypothesis, a synthetic follow-up record, and an accepted AI suggestion.",
            "",
            f"- **Checklist Pass Rate:** {ai3['checklist_passed_count']}/{ai3['total_draft_evaluations']} ({ai3['checklist_pass_rate']:.1%})",
            f"- **Fabricated Reference Leakage Rate:** {ai3['fabricated_id_leak_rate']:.1%}",
            f"- **Draft pipeline duration ({source}):** mean {ai3['mean_latency_ms']:.1f} ms (min: {ai3['min_latency_ms']:.1f} ms, max: {ai3['max_latency_ms']:.1f} ms)",
            "- Reference leakage is checked after production sanitization. It is not a measurement of raw model hallucinations or model accuracy.",
            "",
            "| Draft Kind | Cites Real Incidents/Entries | Invents No IDs | Valid Structure | Latency (ms) | Checklist Result |",
            "|---|---|---|---|---|---|",
        ])
        for d in ai3["drafts"]:
            res_str = "Passed" if d["checklist_passed"] else "Failed"
            c_real = "Yes" if d["cites_real_incidents"] else "No"
            no_inv = "Yes (0 leaked)" if d["invents_no_ids"] else f"No ({', '.join(d['leaked_fabricated_ids'])})"
            v_struct = "Yes" if d["valid_structure"] else "No"
            lines.append(
                f"| `{d['kind']}` | {c_real} | {no_inv} | {v_struct} | {d['duration_ms']:.1f} | **{res_str}** |"
            )

    if "ai4" in results:
        ai4 = results["ai4"]
        lines.extend([
            "",
            "### AI-4: Triage Severity Classification",
            "",
            f"Evaluated against the {ai4['total_cases']} hand-labelled cases in `ai-service/data/triage_eval_set.jsonl` using PR #283's `POST /triage` endpoint (called by `TriageHintService` in the backend).",
            "Every count and percentage below comes from the real rule-based _classify_severity code in main.py through that endpoint; no live model or scripted provider is involved.",
            "",
            f"- **Overall Triage Accuracy:** {ai4['exact_matches']}/{ai4['total_cases']} ({ai4['accuracy']:.1%})",
            "",
            "| Severity Level | Labelled Cases | Correctly Classified | Accuracy |",
            "|---|---|---|---|",
        ])
        for sev, stats in ai4["per_severity"].items():
            lines.append(f"| **{sev}** | {stats['total']} | {stats['correct']} | {stats['accuracy']:.1%} |")

    lines.extend([
        "",
        "### Notes",
        "",
        "- Evaluation runner: `ai-service/ai_evaluation.py`",
        "- Automated unit tests: `ai-service/tests/test_ai_evaluation.py`",
        "- AI-4 scores PR #283's `POST /triage` endpoint (`TriageHintService` backend integration) against `ai-service/data/triage_eval_set.jsonl`.",
        "- Each AI-2/AI-3 section identifies its actual provider. Scripted checks exercise the pipeline and must not be presented as live model results.",
    ])

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# CLI Entrypoint
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="AI Features Evaluation Runner (Issue #252)")
    parser.add_argument("--eval", choices=["all", "ai2", "ai3", "ai4"], default="all", help="Which evaluation to run")
    parser.add_argument("--json", action="store_true", help="Output results as JSON")
    parser.add_argument("--markdown", action="store_true", help="Output results as Markdown")
    parser.add_argument("--save-json", type=str, default=None, help="Optional path to save raw JSON results")
    parser.add_argument("--save-markdown", type=str, default=None, help="Optional path to save Markdown report (UTF-8, no BOM)")
    parser.add_argument("--use-mock", "--mock", action="store_true", help="Run hermetic scripted pipeline checks (no live model)")
    parser.add_argument("--model", default=get_agent_model(), help="Model name for OllamaProvider and report")
    parser.add_argument("--timeout", type=float, default=None, help="Per-scenario / per-draft timeout in seconds")
    parser.add_argument("--commit", default=None, help="Commit SHA identifier for report (defaults to git HEAD)")
    args = parser.parse_args()

    commit_sha = args.commit or _detect_git_commit()

    async def _run() -> None:
        if args.eval == "ai2":
            res: Dict[str, Any] = {
                "ai2": await evaluate_ai2_scenarios(
                    use_mock=args.use_mock, model=args.model, timeout_seconds=args.timeout
                )
            }
        elif args.eval == "ai3":
            res = {
                "ai3": await evaluate_ai3_drafting(
                    use_mock=args.use_mock,
                    model=args.model,
                    timeout_seconds=args.timeout if args.timeout is not None else 60.0,
                )
            }
        elif args.eval == "ai4":
            res = {"ai4": evaluate_ai4_triage()}
        else:
            res = await run_all_evaluations(
                use_mock=args.use_mock, model=args.model, timeout_seconds=args.timeout
            )

        if args.save_json:
            Path(args.save_json).write_text(json.dumps(res, indent=2) + "\n", encoding="utf-8", newline="\n")
        if args.save_markdown:
            md_out = format_markdown_report(res, args.model, commit_sha, datetime.date.today().isoformat())
            Path(args.save_markdown).write_text(md_out + "\n", encoding="utf-8", newline="\n")

        if args.json:
            print(json.dumps(res, indent=2))
        elif args.markdown:
            print(format_markdown_report(res, args.model, commit_sha, datetime.date.today().isoformat()))
        else:
            print("=================================================================")
            print("                   AI EVALUATION RUN RESULTS                     ")
            print("=================================================================")
            if "ai2" in res:
                ai2 = res["ai2"]
                metric = "Top-1 Accuracy" if ai2["provider"] == "ollama" else "Cause-match check"
                print(f"[AI-2 {_provider_description(ai2, 'ScenarioEvaluatorProvider')}] {metric}: {ai2['top_1_matches']}/{ai2['total_scenarios']} ({ai2['top_1_accuracy']:.1%})")
                print(f"                        Mean Latency:   {ai2['mean_time_to_first_hypothesis_ms']:.1f} ms")
            if "ai3" in res:
                ai3 = res["ai3"]
                print(f"[AI-3 {_provider_description(ai3, 'DraftingAdversarialProvider')}] Checklist Pass: {ai3['checklist_passed_count']}/{ai3['total_draft_evaluations']} ({ai3['checklist_pass_rate']:.1%})")
                print(f"                        Fabricated Leak: {ai3['fabricated_id_leak_rate']:.1%}")
                print(f"                        Mean Latency:   {ai3['mean_latency_ms']:.1f} ms")
            if "ai4" in res:
                ai4 = res["ai4"]
                print(f"[AI-4 Triage]           Accuracy:       {ai4['exact_matches']}/{ai4['total_cases']} ({ai4['accuracy']:.1%})")
            print("=================================================================")

    asyncio.run(_run())


if __name__ == "__main__":
    main()
