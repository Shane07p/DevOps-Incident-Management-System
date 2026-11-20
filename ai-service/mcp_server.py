"""
mcp_server.py - Sprint 4 (Issue #162) & Sprint 5 (Issue #248)
--------------------------------------------------------------
FastMCP Streamable HTTP MCP server with authenticated read and write tools for Settle.

Architecture:
  - FastMCP using Streamable HTTP transport (runs as standalone process on port 8001).
  - Every tool calls Settle's backend REST API using the caller's personal access token (PAT).
  - Authentication is passed exclusively via connection headers (Authorization: Bearer <token>).
  - Personal access tokens NEVER appear as tool arguments (prevents model leakage in logs/transcripts).
  - Backend performs authentication and permission enforcement (D38).
  - The MCP server never touches the database directly.
  - Strict write tools: exactly two write tools (`add_comment` and `acknowledge`). No status-changing,
    lifecycle, declare, or close write tools are permitted.
  - Tokens are never logged, persisted, or returned in error messages.

Read tools (active):
  - `list_incidents`: List all incidents, newest first (`GET /api/incidents`).
  - `get_incident`: Incident details with timeline (`GET /api/incidents/{id}`).
  - `search_incidents`: Search incidents by keyword & filters (`GET /api/incidents/search`).
  - `get_service`: Service owner, criticality, and SLA targets (`GET /api/services/{id}`).
  - `who_is_on_call`: Current on-call schedule and fallback (`GET /api/services/{id}/on-call`).
  - `list_alerts`: Alerts inbox (`GET /api/alerts`).
  - `service_map`: Service dependency graph & health status (`GET /api/service-map`).
  - `get_runbook`: Service runbooks and mitigation steps (`GET /api/services/{id}/runbooks`).

Write tools (active, Sprint 5):
  - `add_comment`: Add a comment to an incident timeline (`POST /api/incidents/{id}/comments`).
  - `acknowledge`: Acknowledge an OPEN incident (`POST /api/incidents/{id}/acknowledge`).
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


def _with_detail(msg: str, resp: httpx.Response) -> str:
    """Append the backend's own error message, when it sent one, to a tool error."""
    detail = ""
    try:
        body = resp.json()
        if isinstance(body, dict):
            detail = body.get("message") or body.get("detail") or str(body)
    except Exception:
        detail = resp.text[:200]
    return f"{msg}: {detail}" if detail else msg


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
        raise ToolError(_with_detail(f"Invalid request parameters for {clean_endpoint} (HTTP {resp.status_code})", resp))
    elif resp.status_code == 409:
        raise ToolError(_with_detail("Conflict: Incident was modified concurrently or is not in an eligible state (HTTP 409). Please refresh and retry.", resp))
    elif resp.status_code == 429:
        retry_after = resp.headers.get("Retry-After", "")
        wait = f"{retry_after} seconds" if retry_after.isdigit() else "a minute"
        raise ToolError(f"Rate limit reached for this access token (HTTP 429). Try again in {wait}.")
    elif resp.status_code >= 500:
        raise ToolError(f"Backend server error: {clean_endpoint} returned HTTP {resp.status_code}")
    elif not resp.is_success:
        # Any other answer (409, a redirect, ...) is not data the caller asked for.
        raise ToolError(_with_detail(f"Backend refused {clean_endpoint} (HTTP {resp.status_code})", resp))

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


async def service_map() -> Any:
    """
    Get the service map including health status and dependencies between services.

    Calls backend GET /api/service-map with the caller's personal access token from headers.
    """
    return await _call_backend_api("api/service-map", method="GET")


async def get_runbook(service_id: int) -> Any:
    """
    Get the runbooks registered for a service.

    Calls backend GET /api/services/{service_id}/runbooks with the caller's personal access token from headers.
    Note: Contract uses GET /api/services/{service_id}/runbooks (not /api/runbooks/{id}).
    """
    if not isinstance(service_id, int) or isinstance(service_id, bool) or service_id <= 0:
        raise ToolError("service_id must be a positive integer")
    return await _call_backend_api(f"api/services/{service_id}/runbooks", method="GET")


# ---------------------------------------------------------------------------
# Write Tools (Sprint 5, Issue #248)
# Personal access token is forwarded from request context; expectedVersion is
# read dynamically from the incident entity to ensure safe concurrency (D38).
# ---------------------------------------------------------------------------

def _require_personal_access_token() -> None:
    """
    Write tools act only with a personal access token: the backend marks what a token does as
    "via MCP", so a browser session token sent here would write without that mark.
    """
    if not get_caller_token().startswith("pat_"):
        raise ToolError(
            "Write tools need a personal access token (it starts with pat_). "
            "Create one on the Access tokens page in Settle."
        )


async def add_comment(incident_id: int, comment: str) -> Any:
    """
    Add a comment to an incident timeline.

    Calls backend POST /api/incidents/{incident_id}/comments with the caller's personal access token from headers.
    Validates incident_id is a positive integer and comment is non-empty up to 5000 characters.
    Comments are independent timeline inserts and show 'via MCP' attribution.
    """
    if not isinstance(incident_id, int) or isinstance(incident_id, bool) or incident_id <= 0:
        raise ToolError("incident_id must be a positive integer")
    if not isinstance(comment, str) or not comment.strip():
        raise ToolError("comment must be a non-empty string")
    clean_comment = comment.strip()
    if len(clean_comment) > 5000:
        raise ToolError("comment must not exceed 5000 characters")
    _require_personal_access_token()

    return await _call_backend_api(
        f"api/incidents/{incident_id}/comments",
        method="POST",
        json_data={"comment": clean_comment},
    )


async def acknowledge(incident_id: int) -> Any:
    """
    Acknowledge an OPEN incident, stopping the response clock.

    Retrieves the current incident details first to read its latest version, then calls
    backend POST /api/incidents/{incident_id}/acknowledge with expectedVersion using
    the caller's personal access token from headers.
    """
    if not isinstance(incident_id, int) or isinstance(incident_id, bool) or incident_id <= 0:
        raise ToolError("incident_id must be a positive integer")
    _require_personal_access_token()

    # Retrieve current incident to obtain latest version dynamically (never hardcoded)
    incident = await _call_backend_api(f"api/incidents/{incident_id}", method="GET")
    if not isinstance(incident, dict) or "version" not in incident:
        raise ToolError(f"Could not determine incident version for incident {incident_id}")

    version = incident["version"]

    return await _call_backend_api(
        f"api/incidents/{incident_id}/acknowledge",
        method="POST",
        json_data={"expectedVersion": version},
    )


# Register active read and write tools on the FastMCP instance
mcp.tool(name="list_incidents")(list_incidents)
mcp.tool(name="get_incident")(get_incident)
mcp.tool(name="search_incidents")(search_incidents)
mcp.tool(name="get_service")(get_service)
mcp.tool(name="who_is_on_call")(who_is_on_call)
mcp.tool(name="list_alerts")(list_alerts)
mcp.tool(name="service_map")(service_map)
mcp.tool(name="get_runbook")(get_runbook)
mcp.tool(name="add_comment")(add_comment)
mcp.tool(name="acknowledge")(acknowledge)


def get_mcp_app() -> Any:
    """Return the ASGI application for Streamable HTTP transport."""
    return mcp.http_app(path="/", transport="streamable-http")


if __name__ == "__main__":
    # Run as standalone MCP server process on port 8001 (configurable via MCP_PORT)
    port = int(os.getenv("MCP_PORT", "8001"))
    mcp.run(transport="streamable-http", port=port)
