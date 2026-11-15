"""
triage.py - AI-4 Triage hint for incident declaration (Issue #247, Issue #252).
--------------------------------------------------------------------------------
Provides severity and service suggestions grounded in docs/2-design/severity-guide.md.
Supports both deterministic rule-based evaluation and optional LLM-assisted triage.
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any, Dict, List, Literal, Optional, Tuple

from pydantic import BaseModel, ConfigDict, Field, alias_generators

from providers import LlmProvider

logger = logging.getLogger("ai-service.triage")

SeverityLevel = Literal["SEV1", "SEV2", "SEV3", "SEV4"]


class TriageRequest(BaseModel):
    """Input payload for incident triage hint."""
    model_config = ConfigDict(populate_by_name=True, alias_generator=alias_generators.to_camel)

    title: str = Field(..., description="Incident title")
    description: Optional[str] = Field(default="", description="Incident symptom description")
    service: Optional[str] = Field(default=None, description="Optional suspected service name")


class TriageResponse(BaseModel):
    """Output payload for incident triage hint (AI-4, FR-18.2)."""
    model_config = ConfigDict(populate_by_name=True, alias_generator=alias_generators.to_camel)

    ai_generated: bool = Field(True, description="AI-generated indicator (FR-18.2)")
    severity: SeverityLevel = Field(..., description="Suggested severity level (SEV1-SEV4)")
    service: Optional[str] = Field(default=None, description="Suggested or confirmed service name")
    reason: str = Field(..., description="Operational rationale citing the severity guide")
    confidence: float = Field(default=0.85, ge=0.0, le=1.0, description="Confidence score")


# Keyword pattern heuristics aligned directly with docs/2-design/severity-guide.md
_SEV1_PATTERNS = [
    re.compile(r"\b(?:total failure|catastrophic|completely (?:down|expired|broken)|100%|data corruption|data loss|cluster outage|all payment(?:s)? completely|all regions|globally)\b", re.I),
    re.compile(r"\b(?:cannot sign in|login block|unrecoverable|active breach|compromise|token generation offline|zero users)\b", re.I),
    re.compile(r"\b(?:primary (?:postgresql|database|db) (?:cluster )?outage)\b", re.I),
    re.compile(r"\b(?:checkout service down globally)\b", re.I),
]

_SEV2_PATTERNS = [
    re.compile(r"\b(?:latency degradation|elevation|elevated latency|spike|deadlock|regression|bad deploy)\b", re.I),
    re.compile(r"\b(?:error rate elevated|failure spike|nullpointerexception|intermittent timeouts|500 internal server error)\b", re.I),
    re.compile(r"\b(?:queue saturation|slow-downs|degradation affecting|major degradation|checkout latency|partial failures)\b", re.I),
    re.compile(r"\b(?:\d{1,3}%\s*(?:of\s*)?[^.]*?(?:failures?|to fail|errors?))\b", re.I),
]

_SEV3_PATTERNS = [
    re.compile(r"\b(?:queue backlog|worker (?:is )?paused|delayed email|export timing out|webhook integration failing)\b", re.I),
    re.compile(r"\b(?:non-critical|pagination error|intermittent gaps|rate-limit hit|slack alert|secondary notification)\b", re.I),
    re.compile(r"\b(?:background (?:order|fulfillment|job|worker|report))\b", re.I),
]

_SEV4_PATTERNS = [
    re.compile(r"\b(?:alignment defect|dark mode|expiry reminder|deprecation warning|broken documentation link|broken link|minor typo|typo in|scheduled (?:non-concurrent|maintenance|routine)|cosmetic|routine maintenance)\b", re.I),
    re.compile(r"\b(?:tooltip|formatting|wordmark|cleanup task)\b", re.I),
]

_KNOWN_SERVICES = {
    "payments": ("payment", "gateway", "stripe", "authoriz", "card", "billing"),
    "checkout": ("checkout", "cart", "purchase", "order submission"),
    "database": ("postgres", "sql", "database", "deadlock", "replica", "reindex", "migration"),
    "auth": ("auth", "login", "jwt", "token", "session", "credential"),
    "order-fulfillment": ("order-fulfillment", "fulfillment", "order queue", "order worker"),
    "notifications": ("notification", "email", "smtp", "sendgrid", "slack", "sms"),
    "telemetry": ("telemetry", "prometheus", "grafana", "metric scraping", "exporter"),
    "frontend": ("frontend", "ui", "wordmark", "tooltip", "dark mode", "pagination"),
    "inventory": ("inventory", "stock", "reservation"),
    "catalog": ("catalog", "search", "faceted"),
    "reporting": ("reporting", "csv export", "sales csv"),
    "runbooks": ("runbook", "documentation"),
    "infrastructure": ("certbot", "infrastructure", "tls certificate"),
}


def infer_service(title: str, description: str = "") -> Optional[str]:
    combined = f"{title} {description}".lower()
    for service_name, keywords in _KNOWN_SERVICES.items():
        if any(kw in combined for kw in keywords):
            return service_name
    return None


def classify_severity_rules(
    title: str,
    description: str = "",
    service: Optional[str] = None,
) -> Tuple[SeverityLevel, str, float]:
    """Deterministically classify incident severity according to severity guide rules."""
    text = f"{title} {description}".strip()

    # 1. Check SEV1 critical patterns
    for pat in _SEV1_PATTERNS:
        if pat.search(text):
            return (
                "SEV1",
                "Critical outage or catastrophic impact on primary business workflow (SEV1). Immediate response required.",
                0.95,
            )

    # 2. Check SEV4 minor / cosmetic patterns (prioritize specific cosmetic/typo triggers before general words)
    for pat in _SEV4_PATTERNS:
        if pat.search(text):
            return (
                "SEV4",
                "Minor operational issue or cosmetic defect with no impact on core transactional flows (SEV4).",
                0.90,
            )

    # 3. Check SEV3 background / isolated patterns
    for pat in _SEV3_PATTERNS:
        if pat.search(text):
            return (
                "SEV3",
                "Moderate or isolated background degradation with operational workarounds (SEV3).",
                0.88,
            )

    # 4. Check SEV2 major degradation patterns
    for pat in _SEV2_PATTERNS:
        if pat.search(text):
            return (
                "SEV2",
                "Major degradation or elevated transaction errors impacting customer workflow (SEV2).",
                0.92,
            )

    # Fallback heuristic based on service criticality
    inferred = service or infer_service(title, description)
    if inferred in ("payments", "checkout", "database", "auth"):
        return (
            "SEV2",
            f"Impacting core critical service '{inferred}'; assigned SEV2 pending further investigation.",
            0.75,
        )
    return (
        "SEV3",
        "Moderate issue on supporting service; assigned SEV3.",
        0.70,
    )


async def suggest_triage(
    request: TriageRequest,
    provider: Optional[LlmProvider] = None,
) -> TriageResponse:
    """Produce a triage hint with suggested severity and service."""
    # When provider is not supplied or fails, fallback cleanly to deterministic rule classifier
    sev, reason, confidence = classify_severity_rules(
        title=request.title,
        description=request.description or "",
        service=request.service,
    )
    svc = request.service or infer_service(request.title, request.description or "")

    return TriageResponse(
        ai_generated=True,
        severity=sev,
        service=svc,
        reason=reason,
        confidence=confidence,
    )
