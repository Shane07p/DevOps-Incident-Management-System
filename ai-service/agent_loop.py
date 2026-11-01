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
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field

from agent_tools import EvidenceItem, ToolRegistry, build_default_tool_registry
from providers import LlmProvider, ModelTurnResponse

logger = logging.getLogger("ai-service.agent_loop")


# ---------------------------------------------------------------------------
# Safety and Resource Configuration
# ---------------------------------------------------------------------------

class AgentLimits(BaseModel):
    """Configurable hard bounds for the agent loop."""
    max_steps: int = Field(default=5, ge=1, le=50, description="Maximum tool execution steps")
    timeout_seconds: float = Field(default=15.0, gt=0.0, le=300.0, description="Total wall-clock deadline in seconds")
    max_tokens: int = Field(default=4000, ge=100, le=100000, description="Total cumulative token budget")

    @classmethod
    def create_validated(
        cls,
        max_steps: Optional[int] = None,
        timeout_seconds: Optional[float] = None,
        max_tokens: Optional[int] = None,
    ) -> AgentLimits:
        """Create and strictly validate limits, falling back to safe defaults if invalid."""
        steps = max_steps if max_steps is not None and 1 <= max_steps <= 50 else 5

        timeout = 15.0
        if timeout_seconds is not None:
            if math.isfinite(timeout_seconds) and 0.1 <= timeout_seconds <= 300.0:
                timeout = timeout_seconds

        tokens = 4000
        if max_tokens is not None and 100 <= max_tokens <= 100000:
            tokens = max_tokens

        return cls(max_steps=steps, timeout_seconds=timeout, max_tokens=tokens)


# ---------------------------------------------------------------------------
# Output Models & Evidence Vault
# ---------------------------------------------------------------------------

class RankedHypothesis(BaseModel):
    """Investigation hypothesis strictly supported by real evidence records."""
    hypothesis: str = Field(..., min_length=1, description="Root cause hypothesis description")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Confidence score between 0.0 and 1.0")
    evidence_refs: List[str] = Field(..., min_length=1, description="List of verified EvidenceItem IDs cited")
    reasoning: Optional[str] = Field(None, description="Explanation linking cited evidence to the hypothesis")


class InvestigationResult(BaseModel):
    """Complete result of an agent investigation."""
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

    def add_many(self, items: List[EvidenceItem]) -> None:
        for it in items:
            self._items[it.id] = it

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
                raw_refs = cand.get("evidence_refs", [])
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
    service: str = Field(..., min_length=1, max_length=100, description="Service to investigate")
    incident_start: str = Field(..., description="ISO-8601 UTC timestamp of incident start")
    incident_end: Optional[str] = Field(None, description="ISO-8601 UTC timestamp of incident end")
    description: Optional[str] = Field(None, max_length=5000, description="Reported incident symptoms or notes")
    max_steps: Optional[int] = Field(None, ge=1, le=50, description="Override maximum tool execution steps")
    timeout_seconds: Optional[float] = Field(None, gt=0.0, le=300.0, description="Override wall-clock timeout in seconds")
    max_tokens: Optional[int] = Field(None, ge=100, le=100000, description="Override token budget")


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
        self.default_limits = default_limits or AgentLimits()

    async def run(
        self,
        request: InvestigationRequest,
        limits_override: Optional[AgentLimits] = None,
    ) -> InvestigationResult:
        """
        Execute the investigation loop for the given request.
        """
        limits = limits_override or AgentLimits.create_validated(
            max_steps=request.max_steps or self.default_limits.max_steps,
            timeout_seconds=request.timeout_seconds or self.default_limits.timeout_seconds,
            max_tokens=request.max_tokens or self.default_limits.max_tokens,
        )

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
            "Every hypothesis MUST include 'hypothesis', 'confidence' (0.0 to 1.0), and 'evidence_refs' (listing exact evidence IDs like 'ev-log-0'). "
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

                    # Store real evidence items in Evidence Vault
                    if tool_result.evidence_items:
                        vault.add_many(tool_result.evidence_items)

                    # Format tool message for conversation context
                    tool_output_str = json.dumps(
                        {
                            "success": tool_result.success,
                            "data": tool_result.data,
                            "evidence_ids": [ev.id for ev in tool_result.evidence_items],
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
