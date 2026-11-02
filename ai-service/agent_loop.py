"""
agent_loop.py - Bounded AI SRE Agent Investigation Tool Loop (Issue #91).
---------------------------------------------------------------------------------
Implements the core investigation loop enforcing:
  1. Maximum tool-call steps per investigation.
  2. Maximum wall-clock execution time deadline across the entire loop.
  3. Cumulative token budget across model turns.
  4. Evidence Vault attributing and strictly verifying all hypotheses against real evidence records.
  5. Unsupported hypotheses (missing, fabricated, or empty evidence citations) are discarded.
  6. Graceful failure on timeout, token exhaustion, provider failure, or tool error.
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import re
import time
import os
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, ConfigDict, Field, alias_generators

from agent_tools import EvidenceItem, ToolRegistry, build_default_tool_registry
from providers import LlmProvider, ModelTurnResponse

logger = logging.getLogger("ai-service.agent_loop")


# ---------------------------------------------------------------------------
# Safety and Resource Configuration
# ---------------------------------------------------------------------------

class AgentLimits(BaseModel):
    """Configurable hard bounds for the agent loop."""
    max_steps: int = Field(default=5, ge=1, le=50, description="Maximum tool execution steps")
    timeout_seconds: float = Field(default=15.0, ge=0.1, le=300.0, description="Total wall-clock deadline in seconds")
    max_tokens: int = Field(default=4000, ge=100, le=100000, description="Total cumulative token budget")

    @classmethod
    def from_env(cls) -> AgentLimits:
        """Build AgentLimits from environment variables, validating bounds strictly.

        Rejects invalid values via Pydantic/ValueError rather than silently falling back to defaults.
        """
        raw_steps = os.getenv("AGENT_MAX_STEPS")
        raw_timeout = os.getenv("AGENT_TIMEOUT_SECONDS")
        raw_tokens = os.getenv("AGENT_MAX_TOKENS")

        data: Dict[str, Any] = {}
        if raw_steps is not None and raw_steps.strip() != "":
            try:
                data["max_steps"] = int(raw_steps)
            except ValueError as exc:
                raise ValueError(f"Invalid AGENT_MAX_STEPS '{raw_steps}': must be an integer between 1 and 50") from exc

        if raw_timeout is not None and raw_timeout.strip() != "":
            try:
                val = float(raw_timeout)
                if not math.isfinite(val):
                    raise ValueError("must be a finite number")
                data["timeout_seconds"] = val
            except ValueError as exc:
                raise ValueError(f"Invalid AGENT_TIMEOUT_SECONDS '{raw_timeout}': must be a finite float between 0.1 and 300.0") from exc

        if raw_tokens is not None and raw_tokens.strip() != "":
            try:
                data["max_tokens"] = int(raw_tokens)
            except ValueError as exc:
                raise ValueError(f"Invalid AGENT_MAX_TOKENS '{raw_tokens}': must be an integer between 100 and 100000") from exc

        return cls.model_validate(data)

    @classmethod
    def create_validated(
        cls,
        max_steps: Optional[int] = None,
        timeout_seconds: Optional[float] = None,
        max_tokens: Optional[int] = None,
    ) -> AgentLimits:
        """Construct AgentLimits validating bounds, raising ValidationError on invalid bounds."""
        kwargs: Dict[str, Any] = {}
        if max_steps is not None:
            kwargs["max_steps"] = max_steps
        if timeout_seconds is not None:
            kwargs["timeout_seconds"] = timeout_seconds
        if max_tokens is not None:
            kwargs["max_tokens"] = max_tokens
        return cls(**kwargs)


# ---------------------------------------------------------------------------
# Output Models & Evidence Vault
# ---------------------------------------------------------------------------

class RankedHypothesis(BaseModel):
    """Investigation hypothesis strictly supported by real evidence records."""
    model_config = ConfigDict(populate_by_name=True, alias_generator=alias_generators.to_camel)

    hypothesis: str = Field(..., min_length=1, description="Root cause hypothesis description")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Confidence score between 0.0 and 1.0")
    evidence_refs: List[str] = Field(..., min_length=1, description="List of verified EvidenceItem IDs cited")
    reasoning: Optional[str] = Field(None, description="Explanation linking cited evidence to the hypothesis")


class InvestigationResult(BaseModel):
    """Complete result of an agent investigation."""
    model_config = ConfigDict(populate_by_name=True, alias_generator=alias_generators.to_camel)

    aiGenerated: bool = Field(True, description="FR-18.2 AI-generated indicator")
    status: str = Field(..., description="Status: completed, timeout, step_limit_exceeded, token_budget_exhausted, provider_error, no_evidence")
    summary: str = Field(..., description="High-level investigation narrative summary")
    hypotheses: List[RankedHypothesis] = Field(default_factory=list, description="Ranked, verified hypotheses")
    evidence: List[EvidenceItem] = Field(default_factory=list, description="All verified evidence gathered during investigation")
    steps_taken: int = Field(..., description="Number of tool calls executed")
    tokens_used: int = Field(..., description="Cumulative model tokens consumed")
    duration_ms: float = Field(..., description="Wall-clock elapsed time in milliseconds")


class EvidenceVault:
    """Storehouse of real evidence records collected during the investigation."""

    def __init__(self):
        self._items: Dict[str, EvidenceItem] = {}

    def add(self, item: EvidenceItem) -> None:
        self._items[item.id] = item

    def add_many(self, items: List[EvidenceItem]) -> List[EvidenceItem]:
        """Add multiple evidence items, ensuring unique IDs.

        If an incoming item's ``id`` collides with an existing one, a new
        identifier is generated by appending ``-N`` (where *N* is the smallest
        positive integer making the ID unique). The modified ``EvidenceItem``
        is stored and returned so callers can reference the true stored IDs.
        """
        stored: List[EvidenceItem] = []
        for it in items:
            new_id = it.id
            suffix = 1
            while new_id in self._items:
                new_id = f"{it.id}-{suffix}"
                suffix += 1
            if new_id != it.id:
                it = EvidenceItem(
                    id=new_id,
                    source_tool=it.source_tool,
                    evidence_type=it.evidence_type,
                    content=it.content,
                    details=it.details,
                )
            self._items[new_id] = it
            stored.append(it)
        return stored

    def get(self, item_id: str) -> Optional[EvidenceItem]:
        return self._items.get(item_id)

    def has(self, item_id: str) -> bool:
        return item_id in self._items

    def all_items(self) -> List[EvidenceItem]:
        return list(self._items.values())

    def validate_and_filter_hypotheses(
        self,
        candidate_hypotheses: List[Dict[str, Any] | RankedHypothesis],
    ) -> List[RankedHypothesis]:
        """
        Validate every hypothesis:
          - Must cite at least one evidence reference.
          - Every single cited ID MUST exist in the EvidenceVault.
          - If ANY cited ID is missing, fabricated, or unknown, the hypothesis is DROPPED.
          - Hypotheses with no evidence or empty citations are DROPPED.
        Returns ranked hypotheses sorted by confidence descending.
        """
        valid_hypotheses: List[RankedHypothesis] = []

        for cand in candidate_hypotheses:
            if isinstance(cand, RankedHypothesis):
                hyp_str = cand.hypothesis
                conf = cand.confidence
                refs = cand.evidence_refs
                reason = cand.reasoning
            elif isinstance(cand, dict):
                hyp_str = str(cand.get("hypothesis", "")).strip()
                try:
                    conf = float(cand.get("confidence", 0.0))
                except (ValueError, TypeError):
                    conf = 0.0
                raw_refs = cand.get("evidenceRefs") if "evidenceRefs" in cand else cand.get("evidence_refs", [])
                refs = [str(r).strip() for r in raw_refs if str(r).strip()] if isinstance(raw_refs, list) else []
                reason = cand.get("reasoning")
            else:
                continue

            if not hyp_str:
                continue

            # Must have at least one citation
            if not refs:
                logger.warning("Dropping hypothesis '%s' due to empty evidence_refs.", hyp_str)
                continue

            # Verify every citation against the Vault
            all_refs_valid = all(self.has(ref_id) for ref_id in refs)
            if not all_refs_valid:
                unknown = [ref_id for ref_id in refs if not self.has(ref_id)]
                logger.warning(
                    "Dropping hypothesis '%s' because cited evidence IDs %s were not found in EvidenceVault.",
                    hyp_str,
                    unknown,
                )
                continue

            # Clamp confidence to [0.0, 1.0]
            conf = max(0.0, min(1.0, conf))

            valid_hypotheses.append(
                RankedHypothesis(
                    hypothesis=hyp_str,
                    confidence=conf,
                    evidence_refs=refs,
                    reasoning=reason,
                )
            )

        # Rank by confidence descending
        valid_hypotheses.sort(key=lambda h: h.confidence, reverse=True)
        return valid_hypotheses


# ---------------------------------------------------------------------------
# Investigation Request Model
# ---------------------------------------------------------------------------

class InvestigationRequest(BaseModel):
    """Input payload to trigger an AI SRE investigation."""
    model_config = ConfigDict(populate_by_name=True, alias_generator=alias_generators.to_camel)

    service: str = Field(..., min_length=1, max_length=100, description="Service to investigate")
    incident_start: str = Field(..., description="ISO-8601 UTC timestamp of incident start")
    incident_end: Optional[str] = Field(None, description="ISO-8601 UTC timestamp of incident end")
    description: Optional[str] = Field(None, max_length=5000, description="Reported incident symptoms or notes")


# ---------------------------------------------------------------------------
# Helper: Extract JSON from model text
# ---------------------------------------------------------------------------

def _extract_hypotheses_from_text(content: str) -> tuple[str, List[Dict[str, Any]]]:
    """Parse JSON or structured hypotheses and summary from model output."""
    summary = "Investigation completed."
    hypotheses_raw: List[Dict[str, Any]] = []

    if not content:
        return summary, hypotheses_raw

    # Check for markdown fenced JSON block
    match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", content, re.DOTALL)
    candidate_json = match.group(1) if match else None

    if not candidate_json:
        # Check if the text itself is raw JSON
        trimmed = content.strip()
        if trimmed.startswith("{") and trimmed.endswith("}"):
            candidate_json = trimmed

    if candidate_json:
        try:
            data = json.loads(candidate_json)
            if isinstance(data, dict):
                summary = data.get("summary", summary)
                hyps = data.get("hypotheses", [])
                if isinstance(hyps, list):
                    hypotheses_raw = hyps
                    return summary, hypotheses_raw
        except Exception:
            pass

    # Fallback: check if content has plain text summary
    summary = content.strip()
    return summary, hypotheses_raw


# ---------------------------------------------------------------------------
# Core Investigation Loop
# ---------------------------------------------------------------------------

class AgentInvestigationLoop:
    """
    Executes a multi-turn, safe tool-calling investigation loop.
    Enforces maximum steps, total wall-clock deadline, and token budgets.
    """

    def __init__(
        self,
        provider: LlmProvider,
        tool_registry: Optional[ToolRegistry] = None,
        default_limits: Optional[AgentLimits] = None,
    ):
        self.provider = provider
        self.tool_registry = tool_registry or build_default_tool_registry()
        self.default_limits = default_limits if default_limits is not None else AgentLimits.from_env()

    async def run(
        self,
        request: InvestigationRequest,
        limits_override: Optional[AgentLimits] = None,
    ) -> InvestigationResult:
        """
        Execute the investigation loop for the given request.
        """
        limits = limits_override or self.default_limits

        start_time = time.monotonic()
        steps_taken = 0
        tokens_used = 0
        vault = EvidenceVault()

        # Build initial conversation context
        system_prompt = (
            "You are an AI Site Reliability Engineer investigating an incident on DevOps Incident Management System. "
            "You have access to evidence tools to inspect recorded logs, metric anomalies, deploy events, and service health. "
            "Call the necessary tools to gather facts. "
            "When done, return a JSON object with 'summary' and 'hypotheses'. "
            "Every hypothesis MUST include 'hypothesis', 'confidence' (0.0 to 1.0), and 'evidenceRefs' (listing exact evidence IDs like 'ev-log-0'). "
            "Do NOT fabricate evidence IDs. Any hypothesis without real evidence will be discarded."
        )

        user_content = (
            f"Investigate incident on service '{request.service}'. "
            f"Incident start: {request.incident_start}. "
            f"Incident end: {request.incident_end or 'ongoing'}. "
        )
        if request.description:
            user_content += f"Symptoms: {request.description}. "

        messages: List[Dict[str, Any]] = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_content},
        ]

        final_summary = "Investigation stopped."
        candidate_hypotheses: List[Dict[str, Any]] = []
        loop_status = "completed"

        while True:
            # 1. Check wall-clock deadline across the complete loop
            elapsed = time.monotonic() - start_time
            if elapsed >= limits.timeout_seconds:
                logger.warning("Investigation deadline exceeded: %.2fs >= %.2fs", elapsed, limits.timeout_seconds)
                loop_status = "timeout"
                final_summary = f"Investigation exceeded wall-clock timeout of {limits.timeout_seconds}s."
                break

            # 2. Check cumulative token budget
            if tokens_used >= limits.max_tokens:
                logger.warning("Investigation token budget exhausted: %d >= %d", tokens_used, limits.max_tokens)
                loop_status = "token_budget_exhausted"
                final_summary = f"Investigation exceeded maximum token budget of {limits.max_tokens} tokens."
                break

            # 3. Check step limit before invoking model
            if steps_taken >= limits.max_steps:
                logger.warning("Investigation maximum step limit reached: %d >= %d", steps_taken, limits.max_steps)
                loop_status = "step_limit_exceeded"
                final_summary = f"Investigation reached maximum limit of {limits.max_steps} tool-call steps."
                break

            # Compute remaining time for this provider call
            remaining_time = max(0.05, limits.timeout_seconds - (time.monotonic() - start_time))

            # 4. Invoke LLM Provider
            try:
                turn_response: ModelTurnResponse = await self.provider.generate(
                    messages=messages,
                    tools=self.tool_registry.list_tools(),
                    timeout=remaining_time,
                )
            except asyncio.TimeoutError:
                loop_status = "timeout"
                final_summary = f"Provider request timed out (deadline: {limits.timeout_seconds}s)."
                break
            except Exception as exc:
                logger.error("Provider failed during investigation: %s", exc)
                loop_status = "provider_error"
                final_summary = f"Provider failure during investigation: {exc}"
                break

            # Accumulate token usage
            tokens_used += turn_response.total_tokens

            # Check token budget post-generation
            if tokens_used > limits.max_tokens:
                loop_status = "token_budget_exhausted"
                final_summary = f"Investigation exceeded token budget ({tokens_used} > {limits.max_tokens})."
                break

            # 5. Process tool calls if requested by model
            if turn_response.tool_calls:
                # Add assistant message with tool calls to context
                messages.append(
                    {
                        "role": "assistant",
                        "content": turn_response.content or "",
                        "tool_calls": [
                            {
                                "id": tc.call_id,
                                "type": "function",
                                "function": {"name": tc.tool_name, "arguments": tc.arguments},
                            }
                            for tc in turn_response.tool_calls
                        ],
                    }
                )

                step_exhausted_mid_turn = False
                for tc in turn_response.tool_calls:
                    # Enforce step count per tool call
                    if steps_taken >= limits.max_steps:
                        logger.warning("Step limit reached while dispatching tool calls.")
                        loop_status = "step_limit_exceeded"
                        final_summary = f"Investigation reached maximum limit of {limits.max_steps} steps."
                        step_exhausted_mid_turn = True
                        break

                    # Check deadline before each tool execution
                    if time.monotonic() - start_time >= limits.timeout_seconds:
                        loop_status = "timeout"
                        final_summary = f"Investigation deadline exceeded during tool execution."
                        step_exhausted_mid_turn = True
                        break

                    steps_taken += 1

                    # Dispatch tool safely through ToolRegistry
                    tool_result = self.tool_registry.execute(tc.tool_name, tc.arguments)

                    # Store real evidence items in Evidence Vault and capture the
                    # *stored* items (which may have collision-resolved IDs).
                    stored_items: List[EvidenceItem] = []
                    if tool_result.evidence_items:
                        stored_items = vault.add_many(tool_result.evidence_items)

                    # Inform the model of the actual stored IDs and their content
                    # so that its citations in hypotheses match what the vault holds.
                    tool_output_str = json.dumps(
                        {
                            "success": tool_result.success,
                            "data": tool_result.data,
                            "evidence": [
                                {"id": ev.id, "content": ev.content}
                                for ev in stored_items
                            ],
                            "error": tool_result.error,
                        }
                    )
                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": tc.call_id,
                            "name": tc.tool_name,
                            "content": tool_output_str,
                        }
                    )

                if step_exhausted_mid_turn:
                    break

            else:
                # 6. Model completed with final narrative answer
                content = turn_response.content or ""
                summary_extracted, candidate_hyps = _extract_hypotheses_from_text(content)
                final_summary = summary_extracted or "Investigation concluded."
                candidate_hypotheses = candidate_hyps
                loop_status = "completed"
                break

        # 7. Validate and filter all candidate hypotheses against the Evidence Vault
        verified_hypotheses = vault.validate_and_filter_hypotheses(candidate_hypotheses)

        # Honest representation: if evidence is absent or no hypotheses survived
        if not verified_hypotheses and loop_status == "completed":
            if not vault.all_items():
                loop_status = "no_evidence"
                final_summary += " No evidence was found during the investigation."
            else:
                final_summary += " No hypotheses were supported by verified evidence."

        total_duration_ms = (time.monotonic() - start_time) * 1000.0

        return InvestigationResult(
            aiGenerated=True,
            status=loop_status,
            summary=final_summary,
            hypotheses=verified_hypotheses,
            evidence=vault.all_items(),
            steps_taken=steps_taken,
            tokens_used=tokens_used,
            duration_ms=round(total_duration_ms, 2),
        )
