"""
mcp_server.py - Sprint 4 (Issue #162)
--------------------------------------
FastMCP Streamable HTTP MCP server with authenticated read tools for Settle.

Architecture:
  - FastMCP using Streamable HTTP transport (runs as standalone process on port 8001).
  - Every tool calls Settle's backend REST API using the caller's personal access token (PAT).
  - Authentication is passed exclusively via connection headers (Authorization: Bearer <token>).
  - Personal access tokens NEVER appear as tool arguments (prevents model leakage in logs/transcripts).
  - Backend performs authentication and permission enforcement (D38).
  - The MCP server never touches the database directly.
  - Strict read-only tools: no write tools (write tools deferred to Sprint 5).
  - Tokens are never logged, persisted, or returned in error messages.

Read tools (active):
  - `list_incidents`: List all incidents, newest first (`GET /api/incidents`).
  - `get_incident`: Incident details with timeline (`GET /api/incidents/{id}`).
  - `search_incidents`: Search incidents by keyword & filters (`GET /api/incidents/search`).
  - `get_service`: Service owner, criticality, and SLA targets (`GET /api/services/{id}`).
  - `who_is_on_call`: Current on-call schedule and fallback (`GET /api/services/{id}/on-call`).
  - `list_alerts`: Alerts inbox (`GET /api/alerts`).
"""

from __future__ import annotations

from contextvars import ContextVar
import logging
import math
import os
from typing import Any, Dict, Optional

from fastmcp import FastMCP
from fastmcp.exceptions import ToolError
import httpx

logger = logging.getLogger("ai-service.mcp")

# Per-request context variable for caller's personal access token (used by tests / middleware)
_current_token_var: ContextVar[Optional[str]] = ContextVar("caller_token", default=None)


def set_request_token(token: Optional[str]) -> None:
    """Set the caller's token in the current async task context (for testing or request binding)."""
    _current_token_var.set(token)


def get_backend_api_url() -> str:
    """Resolve backend REST API base URL from environment (standardized to SETTLE_API_URL)."""
    return os.getenv("SETTLE_API_URL", "http://localhost:8080").rstrip("/")


def get_backend_timeout() -> float:
    """Resolve backend request timeout in seconds (standardized to SETTLE_API_TIMEOUT)."""
    raw = os.getenv("SETTLE_API_TIMEOUT", "10")
    try:
        val = float(raw)
        if not math.isfinite(val) or val <= 0:
            return 10.0
        return val
    except (ValueError, TypeError):
        return 10.0


def get_caller_token() -> str:
    """
    Extract caller's personal access token from incoming HTTP request Authorization header
    or active task context variable (for tests / middleware).

    Tokens are NEVER accepted as tool arguments (avoids leaking credentials through the model).
    Raises ToolError if no token is found.
    Never exposes or logs the token.
    """
    # 1. Per-task context variable (testing or middleware binding)
    ctx_token = _current_token_var.get(None)
    if ctx_token and isinstance(ctx_token, str) and ctx_token.strip():
        return ctx_token.strip()

    # 2. FastMCP incoming HTTP request header (Authorization: Bearer <token>)
    try:
        from fastmcp.server.dependencies import get_http_request
        req = get_http_request()
        auth_header = req.headers.get("authorization") or req.headers.get("Authorization")
        if auth_header and isinstance(auth_header, str):
            if auth_header.lower().startswith("bearer "):
                extracted = auth_header[7:].strip()
                if extracted:
                    return extracted
            elif auth_header.strip():
                return auth_header.strip()
    except Exception:
        # No active HTTP request context (e.g. direct call without header)
        pass

    raise ToolError(
        "Missing personal access token. Please provide your personal access token via "
        "Authorization: Bearer <token> header."
    )


async def _call_backend_api(
    endpoint: str,
    method: str = "GET",
    params: Optional[Dict[str, Any]] = None,
    json_data: Optional[Dict[str, Any]] = None,
) -> Any:
    """
    Execute an authenticated HTTP request against Settle backend REST API.

    Enforces caller token forwarding, timeouts, and safe error translation.
    Never includes tokens in logged output, exceptions, or error details.
    """
    resolved_token = get_caller_token()

    headers = {
        "Authorization": f"Bearer {resolved_token}",
        "Accept": "application/json",
    }

    base_url = get_backend_api_url()
    clean_endpoint = endpoint.lstrip("/")
    url = f"{base_url}/{clean_endpoint}"
    timeout = get_backend_timeout()

    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.request(
                method,
                url,
                headers=headers,
                params=params,
                json=json_data,
            )
    except httpx.TimeoutException:
        raise ToolError(f"Backend request timed out after {timeout} seconds ({clean_endpoint})")
    except httpx.RequestError as exc:
        raise ToolError(f"Failed to connect to Settle backend API: {exc.__class__.__name__}")

    # Map HTTP status codes to clear, readable tool errors
    if resp.status_code == 401:
        raise ToolError("Authentication failed: Personal access token is invalid, expired, or revoked (HTTP 401)")
    elif resp.status_code == 403:
        raise ToolError("Permission denied: Your account role does not have access to this resource (HTTP 403)")
    elif resp.status_code == 404:
        raise ToolError(f"Resource not found: {clean_endpoint} returned HTTP 404")
    elif resp.status_code in (400, 422):
        detail = ""
        try:
            body = resp.json()
            if isinstance(body, dict):
                detail = body.get("message") or body.get("detail") or str(body)
        except Exception:
            detail = resp.text[:200]
        msg = f"Invalid request parameters for {clean_endpoint} (HTTP {resp.status_code})"
        if detail:
            msg += f": {detail}"
        raise ToolError(msg)
    elif resp.status_code >= 500:
        raise ToolError(f"Backend server error: {clean_endpoint} returned HTTP {resp.status_code}")

    try:
        return resp.json()
    except Exception:
        return resp.text


# ---------------------------------------------------------------------------
# FastMCP Server Instance
# ---------------------------------------------------------------------------

mcp = FastMCP(
    name="settle",
    instructions="Settle Incident Management System MCP server providing read access to incidents, services, on-call schedules, and alerts.",
)


# ---------------------------------------------------------------------------
# Read Tools (Sprint 4, Issue #162)
# Notice: Zero tool functions accept `token` parameter (Shane Review Fix 1)
# ---------------------------------------------------------------------------

async def list_incidents() -> Any:
    """
    List all incidents in Settle, newest first.

    Calls backend GET /api/incidents with the caller's personal access token from headers.
    """
    return await _call_backend_api("api/incidents", method="GET")


async def get_incident(incident_id: int) -> Any:
    """
    Get incident details including its full timeline (status updates, comments, and role assignments).

    Calls backend GET /api/incidents/{incident_id} with the caller's personal access token from headers.
    """
    if not isinstance(incident_id, int) or incident_id <= 0:
        raise ToolError("incident_id must be a positive integer")
    return await _call_backend_api(f"api/incidents/{incident_id}", method="GET")


async def search_incidents(
    query: Optional[str] = None,
    service_id: Optional[int] = None,
    severity: Optional[str] = None,
    status: Optional[str] = None,
    page: Optional[int] = 0,
) -> Any:
    """
    Search past incidents by keyword with optional filters for service, severity, status, and pagination.

    Calls backend GET /api/incidents/search with the caller's personal access token from headers.
    Note: Page size is fixed at 20 by the backend contract (no size parameter).
    """
    params: Dict[str, Any] = {}
    if query and query.strip():
        params["q"] = query.strip()
    if service_id is not None:
        params["serviceId"] = service_id
    if severity and severity.strip():
        params["severity"] = severity.strip()
    if status and status.strip():
        params["status"] = status.strip()
    if page is not None:
        params["page"] = page

    return await _call_backend_api("api/incidents/search", method="GET", params=params)


async def get_service(service_id: int) -> Any:
    """
    Get service details (name, owner, criticality, SLA targets) for a registered service.

    Calls backend GET /api/services/{service_id} with the caller's personal access token from headers.
    """
    if not isinstance(service_id, int) or service_id <= 0:
        raise ToolError("service_id must be a positive integer")
    return await _call_backend_api(f"api/services/{service_id}", method="GET")


async def who_is_on_call(service_id: int) -> Any:
    """
    Get the on-call schedule, primary shift engineer, and fallback contact for a service.

    Calls backend GET /api/services/{service_id}/on-call with the caller's personal access token from headers.
    """
    if not isinstance(service_id, int) or service_id <= 0:
        raise ToolError("service_id must be a positive integer")
    return await _call_backend_api(f"api/services/{service_id}/on-call", method="GET")


async def list_alerts() -> Any:
    """
    List inbox alerts received from monitoring integrations.

    Calls backend GET /api/alerts with the caller's personal access token from headers.
    """
    return await _call_backend_api("api/alerts", method="GET")


# Register active read tools on the FastMCP instance
mcp.tool(name="list_incidents")(list_incidents)
mcp.tool(name="get_incident")(get_incident)
mcp.tool(name="search_incidents")(search_incidents)
mcp.tool(name="get_service")(get_service)
mcp.tool(name="who_is_on_call")(who_is_on_call)
mcp.tool(name="list_alerts")(list_alerts)


def get_mcp_app() -> Any:
    """Return the ASGI application for Streamable HTTP transport."""
    return mcp.http_app(path="/", transport="streamable-http")


if __name__ == "__main__":
    # Run as standalone MCP server process on port 8001
    mcp.run(transport="streamable-http", port=8001)
