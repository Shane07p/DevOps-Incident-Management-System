"""
providers.py - LLM Provider Abstraction for Sprint 3 (Issue #91).
-----------------------------------------------------------------------------
Provides a model provider interface, a deterministic MockProvider for unit tests,
and an OllamaProvider for local inference with function calling and token tracking.
"""

from __future__ import annotations

import asyncio
from abc import ABC, abstractmethod
import json
import logging
from typing import Any, Callable, Dict, List, Optional
from pydantic import BaseModel, Field

import httpx

logger = logging.getLogger("ai-service.providers")


# ---------------------------------------------------------------------------
# Provider Data Models
# ---------------------------------------------------------------------------

class ToolCallRequest(BaseModel):
    """Structured tool invocation request produced by the model."""
    call_id: str = Field(default="", description="Identifier for this tool call")
    tool_name: str = Field(..., description="Name of the tool to invoke")
    arguments: Dict[str, Any] = Field(default_factory=dict, description="Parsed arguments")


class ModelTurnResponse(BaseModel):
    """Output produced by one LLM generation turn."""
    content: Optional[str] = None
    tool_calls: List[ToolCallRequest] = Field(default_factory=list)
    finish_reason: str = "stop"  # "stop", "tool_calls", "length"
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0


# ---------------------------------------------------------------------------
# Base Provider Interface
# ---------------------------------------------------------------------------

class LlmProvider(ABC):
    """Abstract interface for model providers in the agent loop."""

    @abstractmethod
    async def generate(
        self,
        messages: List[Dict[str, Any]],
        tools: List[Dict[str, Any]],
        timeout: float = 10.0,
        max_output_tokens: Optional[int] = None,
    ) -> ModelTurnResponse:
        """
        Execute one model turn given the conversation history and registered tools. The model may
        generate at most ``max_output_tokens`` tokens, when given.
        """
        pass


# ---------------------------------------------------------------------------
# Deterministic Mock Provider (for offline tests & CI)
# ---------------------------------------------------------------------------

class MockProvider(LlmProvider):
    """
    Deterministic provider replaying scripted responses for hermetic unit testing.
    Supports fixed responses, programmed token counts, synthetic delays, and exceptions.
    """

    def __init__(
        self,
        responses: Optional[List[ModelTurnResponse | Exception | Callable[..., ModelTurnResponse]]] = None,
        default_tokens_per_turn: int = 50,
        artificial_delay_seconds: float = 0.0,
    ):
        self._responses: List[Any] = list(responses) if responses else []
        self._turn_index: int = 0
        self._default_tokens = default_tokens_per_turn
        self._delay = artificial_delay_seconds
        self.call_history: List[Dict[str, Any]] = []

    def add_response(self, response: ModelTurnResponse | Exception | Callable[..., ModelTurnResponse]) -> None:
        self._responses.append(response)

    async def generate(
        self,
        messages: List[Dict[str, Any]],
        tools: List[Dict[str, Any]],
        timeout: float = 10.0,
        max_output_tokens: Optional[int] = None,
    ) -> ModelTurnResponse:
        self.call_history.append(
            {"messages": messages, "tools": tools, "timeout": timeout, "max_output_tokens": max_output_tokens}
        )

        if self._delay > 0:
            if self._delay >= timeout:
                await asyncio.sleep(timeout)
                raise asyncio.TimeoutError(f"MockProvider artificial delay exceeded timeout ({timeout}s)")
            await asyncio.sleep(self._delay)

        if self._turn_index >= len(self._responses):
            # Default fallback response if script runs out
            return ModelTurnResponse(
                content="No further instructions.",
                finish_reason="stop",
                prompt_tokens=self._default_tokens,
                completion_tokens=self._default_tokens,
                total_tokens=self._default_tokens * 2,
            )

        resp = self._responses[self._turn_index]
        self._turn_index += 1

        if isinstance(resp, Exception):
            raise resp
        elif callable(resp):
            result = resp(messages, tools)
            return result
        elif isinstance(resp, ModelTurnResponse):
            return resp
        else:
            raise TypeError(f"Unsupported mock response type: {type(resp)}")


# ---------------------------------------------------------------------------
# Ollama Provider (Sprint 2 / Sprint 3 local integration)
# ---------------------------------------------------------------------------

class OllamaProvider(LlmProvider):
    """
    Provider integrating with local Ollama daemon via /api/chat.
    Conforms to local-only requirements (D28, NFR-12.1).
    """

    def __init__(
        self,
        base_url: str = "http://localhost:11434",
        model: str = "qwen2.5:0.5b",
        http_client: Optional[httpx.AsyncClient] = None,
    ):
        self.base_url = base_url.rstrip("/")
        self.model = model
        self._client = http_client

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient()
        return self._client

    async def generate(
        self,
        messages: List[Dict[str, Any]],
        tools: List[Dict[str, Any]],
        timeout: float = 10.0,
        max_output_tokens: Optional[int] = None,
    ) -> ModelTurnResponse:
        client = await self._get_client()

        payload: Dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "stream": False,
        }
        if tools:
            payload["tools"] = tools
        if max_output_tokens is not None:
            # Ollama stops generating after num_predict tokens.
            payload["options"] = {"num_predict": max_output_tokens}

        try:
            resp = await client.post(
                f"{self.base_url}/api/chat",
                json=payload,
                timeout=timeout,
            )
        except httpx.TimeoutException as exc:
            raise asyncio.TimeoutError(f"Ollama chat request timed out after {timeout}s") from exc
        except (httpx.HTTPError, httpx.InvalidURL) as exc:
            raise RuntimeError(f"Ollama connection error: {exc}") from exc

        if resp.status_code != 200:
            raise RuntimeError(f"Ollama returned HTTP {resp.status_code}: {resp.text[:200]}")

        data = resp.json()
        message = data.get("message", {})
        content = message.get("content")
        raw_tool_calls = message.get("tool_calls", [])

        tool_calls: List[ToolCallRequest] = []
        for idx, tc in enumerate(raw_tool_calls):
            func = tc.get("function", {})
            name = func.get("name", "")
            args = func.get("arguments", {})
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except Exception:
                    args = {}
            tool_calls.append(
                ToolCallRequest(
                    call_id=f"call_{idx}",
                    tool_name=name,
                    arguments=args,
                )
            )

        finish_reason = "tool_calls" if tool_calls else "stop"

        # Extract tokens from Ollama response metadata
        prompt_tokens = data.get("prompt_eval_count", 0) or 0
        completion_tokens = data.get("eval_count", 0) or 0
        total_tokens = prompt_tokens + completion_tokens

        return ModelTurnResponse(
            content=content,
            tool_calls=tool_calls,
            finish_reason=finish_reason,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            total_tokens=total_tokens,
        )
