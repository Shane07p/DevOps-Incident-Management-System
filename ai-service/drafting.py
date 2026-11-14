"""
drafting.py - AI-3 Drafting for summaries, status updates, and postmortems (Issue #246).
---------------------------------------------------------------------------------------
Generates human-editable drafts grounded strictly in the provided incident, timeline,
accepted hypotheses, and follow-ups. Strips any fabricated incident or entry references.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any, Dict, List, Literal, Optional, Set

from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field, alias_generators

from providers import LlmProvider

logger = logging.getLogger("ai-service.drafting")

_INC_REF_RE = re.compile(r"\bINC-(\d+)\b", re.IGNORECASE)
_ENTRY_REF_RE = re.compile(r"\b(?:timeline\s+)?entry\s*#?(\d+)\b", re.IGNORECASE)
_INCIDENT_NUM_RE = re.compile(r"\bincident\s*#(\d+)\b", re.IGNORECASE)


class DraftRequest(BaseModel):
    """Input payload for POST /draft (AI-3, FR-11.4)."""
    model_config = ConfigDict(populate_by_name=True, alias_generator=alias_generators.to_camel)

    kind: Literal["SUMMARY", "STATUS_UPDATE", "POSTMORTEM"]
    incident: Dict[str, Any]
    timeline: List[Dict[str, Any]] = Field(default_factory=list)
    accepted_hypotheses: List[Dict[str, Any]] = Field(default_factory=list)
    follow_ups: List[str] = Field(default_factory=list)


class PostmortemDraft(BaseModel):
    """Structured postmortem sections for human editing."""
    model_config = ConfigDict(populate_by_name=True, alias_generator=alias_generators.to_camel)

    summary: str = ""
    impact: str = ""
    root_cause: str = ""
    resolution: str = ""
    went_well: str = ""
    went_badly: str = ""


class DraftResponse(BaseModel):
    """Output payload for POST /draft."""
    model_config = ConfigDict(populate_by_name=True, alias_generator=alias_generators.to_camel)

    ai_generated: bool = Field(True, description="FR-18.2 AI-generated indicator")
    kind: Literal["SUMMARY", "STATUS_UPDATE", "POSTMORTEM"]
    text: str
    postmortem: Optional[PostmortemDraft] = None


def _allowed_refs(request: DraftRequest) -> tuple[Set[str], Set[str], Set[str]]:
    """Collect valid incident references, numeric incident IDs, and timeline entry IDs."""
    inc_refs: Set[str] = set()
    inc_nums: Set[str] = set()
    entry_ids: Set[str] = set()

    inc = request.incident or {}
    if ref := inc.get("reference"):
        inc_refs.add(str(ref).strip().upper())
        m = _INC_REF_RE.search(str(ref))
        if m:
            inc_nums.add(m.group(1))
    if inc_id := inc.get("id"):
        inc_nums.add(str(inc_id).strip())
    if inc_num := inc.get("number"):
        inc_nums.add(str(inc_num).strip())
        inc_refs.add(f"INC-{inc_num}")

    # Any incident references explicitly present in the provided incident text or timeline details
    for text_val in [inc.get("title"), inc.get("description")]:
        if isinstance(text_val, str):
            for m in _INC_REF_RE.finditer(text_val):
                inc_refs.add(f"INC-{m.group(1)}")
                inc_nums.add(m.group(1))

    for entry in request.timeline:
        if (eid := entry.get("id")) is not None:
            entry_ids.add(str(eid).strip())
        if isinstance(details := entry.get("details"), str):
            for m in _INC_REF_RE.finditer(details):
                inc_refs.add(f"INC-{m.group(1)}")
                inc_nums.add(m.group(1))

    return inc_refs, inc_nums, entry_ids


def sanitize_draft_text(
    text: str,
    allowed_inc_refs: Set[str],
    allowed_inc_nums: Set[str],
    allowed_entry_ids: Set[str],
) -> str:
    """Drop any incident or timeline entry references not present in the input."""
    if not text:
        return ""

    def replace_inc_ref(match: re.Match[str]) -> str:
        normalized = f"INC-{match.group(1)}"
        return match.group(0) if normalized in allowed_inc_refs else ""

    def replace_inc_num(match: re.Match[str]) -> str:
        return match.group(0) if match.group(1) in allowed_inc_nums else ""

    def replace_entry_ref(match: re.Match[str]) -> str:
        return match.group(0) if match.group(1) in allowed_entry_ids else ""

    cleaned = _INC_REF_RE.sub(replace_inc_ref, text)
    cleaned = _INCIDENT_NUM_RE.sub(replace_inc_num, cleaned)
    cleaned = _ENTRY_REF_RE.sub(replace_entry_ref, cleaned)
    # Clean up stray empty parentheses/brackets and extra spaces left by removals
    cleaned = re.sub(r"\(\s*\)|\[\s*\]", "", cleaned)
    cleaned = re.sub(r"[ \t]{2,}", " ", cleaned)
    cleaned = re.sub(r"\s+([.,;:])", r"\1", cleaned)
    return cleaned.strip()


def _extract_json_dict(content: str) -> Optional[Dict[str, Any]]:
    if not content:
        return None
    match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", content, re.DOTALL)
    candidate = match.group(1) if match else None
    if not candidate:
        trimmed = content.strip()
        if trimmed.startswith("{") and trimmed.endswith("}"):
            candidate = trimmed
    if not candidate:
        return None
    try:
        parsed = json.loads(candidate)
        return parsed if isinstance(parsed, dict) else None
    except Exception:
        return None


def _build_messages(request: DraftRequest) -> List[Dict[str, Any]]:
    kind = request.kind
    if kind == "POSTMORTEM":
        format_instruction = (
            "Return a JSON object with 'text' (a brief overview) and 'postmortem' containing: "
            "'summary', 'impact', 'rootCause', 'resolution', 'wentWell', 'wentBadly'."
        )
    elif kind == "STATUS_UPDATE":
        format_instruction = (
            "Write a concise, clear external/internal status update for stakeholders. "
            "Return a JSON object with 'text' containing the status update."
        )
    else:
        format_instruction = (
            "Write a concise incident summary covering what happened, current status, and key findings. "
            "Return a JSON object with 'text' containing the summary."
        )

    system_prompt = (
        "You are an AI incident scribe drafting text for a human engineer to review and edit before saving. "
        "Cite ONLY the incident reference and timeline entry IDs provided in the input. "
        "NEVER invent or guess any incident IDs (such as INC-999) or entry IDs. "
        + format_instruction
    )

    payload = {
        "kind": kind,
        "incident": request.incident,
        "timeline": request.timeline,
        "acceptedHypotheses": request.accepted_hypotheses,
        "followUps": request.follow_ups,
    }
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": json.dumps(payload, default=str)},
    ]


async def generate_draft(
    request: DraftRequest,
    provider: LlmProvider,
    timeout: float = 15.0,
) -> DraftResponse:
    """Call the configured LLM provider to generate a draft and sanitize references."""
    messages = _build_messages(request)
    try:
        turn = await asyncio.wait_for(
            provider.generate(messages=messages, tools=[], timeout=timeout),
            timeout=timeout,
        )
    except asyncio.TimeoutError as exc:
        logger.warning("Draft generation timed out after %.1fs", timeout)
        raise HTTPException(
            status_code=503,
            detail="AI drafting service timed out. Please try again later.",
        ) from exc
    except Exception as exc:
        logger.warning("Draft generation provider error: %s", exc)
        raise HTTPException(
            status_code=503,
            detail="AI drafting service is unavailable right now.",
        ) from exc

    raw_content = (turn.content or "").strip()
    parsed = _extract_json_dict(raw_content)

    inc_refs, inc_nums, entry_ids = _allowed_refs(request)

    if request.kind == "POSTMORTEM":
        pm_raw = {}
        text = ""
        if parsed:
            text = str(parsed.get("text") or "").strip()
            candidate_pm = parsed.get("postmortem")
            if isinstance(candidate_pm, dict):
                pm_raw = candidate_pm
            else:
                pm_raw = parsed

        pm = PostmortemDraft(
            summary=sanitize_draft_text(str(pm_raw.get("summary") or ""), inc_refs, inc_nums, entry_ids),
            impact=sanitize_draft_text(str(pm_raw.get("impact") or ""), inc_refs, inc_nums, entry_ids),
            root_cause=sanitize_draft_text(
                str(pm_raw.get("rootCause") or pm_raw.get("root_cause") or ""),
                inc_refs,
                inc_nums,
                entry_ids,
            ),
            resolution=sanitize_draft_text(str(pm_raw.get("resolution") or ""), inc_refs, inc_nums, entry_ids),
            went_well=sanitize_draft_text(
                str(pm_raw.get("wentWell") or pm_raw.get("went_well") or ""),
                inc_refs,
                inc_nums,
                entry_ids,
            ),
            went_badly=sanitize_draft_text(
                str(pm_raw.get("wentBadly") or pm_raw.get("went_badly") or ""),
                inc_refs,
                inc_nums,
                entry_ids,
            ),
        )
        if not text:
            text = pm.summary or sanitize_draft_text(raw_content, inc_refs, inc_nums, entry_ids)
        else:
            text = sanitize_draft_text(text, inc_refs, inc_nums, entry_ids)
        return DraftResponse(kind=request.kind, text=text, postmortem=pm)

    text = str(parsed.get("text") or "").strip() if parsed else raw_content
    text = sanitize_draft_text(text, inc_refs, inc_nums, entry_ids)
    return DraftResponse(kind=request.kind, text=text, postmortem=None)
