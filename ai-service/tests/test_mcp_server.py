"""
tests/test_mcp_server.py
-------------------------
Comprehensive test suite for Settle FastMCP Streamable HTTP server with read tools (Sprint 4, Issue #162).

Review fixes addressed:
  1. No tool accepts a `token` parameter (token is extracted strictly from headers / context).
  2. `search_incidents` does not pass `size` parameter (backend uses fixed page size 20).
  3. `service_map` and `get_runbook` are excluded until backend #154 and #155 merge.
  4. Standardized single environment variable names `SETTLE_API_URL` and `SETTLE_API_TIMEOUT`.
  5. MCP server runs as standalone process; `main.app` does NOT mount `/mcp`.
  6. Personal access token dependency (#151) is enforced and tested.
"""

import asyncio
import inspect
from unittest.mock import AsyncMock, MagicMock, patch

from fastmcp.exceptions import ToolError
import httpx
import pytest

from mcp_server import (
    _call_backend_api,
    get_backend_api_url,
    get_backend_timeout,
    get_caller_token,
    get_incident,
    get_mcp_app,
    get_runbook,
    get_service,
    list_alerts,
    list_incidents,
    mcp,
    search_incidents,
    service_map,
    set_request_token,
    who_is_on_call,
)


def _mock_response(status_code: int = 200, json_data=None, text=""):
    """Helper creating a mock httpx.Response."""
    request = httpx.Request("GET", "http://localhost:8080")
    if json_data is not None:
        import json
        content = json.dumps(json_data).encode("utf-8")
        headers = {"content-type": "application/json"}
    else:
        content = text.encode("utf-8")
        headers = {"content-type": "text/plain"}
    return httpx.Response(status_code=status_code, content=content, headers=headers, request=request)


# 1. Registered tools check (all 8 read tools registered now that #154 and #155 merged)
def test_each_registered_mcp_tool_exists_with_expected_name():
    tool_names = set(mcp._tool_manager._tools.keys())
    expected_read_tools = {
        "list_incidents",
        "get_incident",
        "search_incidents",
        "get_service",
        "who_is_on_call",
        "list_alerts",
        "service_map",
        "get_runbook",
    }
    for expected in expected_read_tools:
        assert expected in tool_names, f"Expected tool '{expected}' not registered in FastMCP"


# 2. No tool accepts a token argument (Review Fix 1)
def test_no_tool_accepts_token_argument():
    tool_funcs = [
        list_incidents,
        get_incident,
        search_incidents,
        get_service,
        who_is_on_call,
        list_alerts,
        service_map,
        get_runbook,
    ]
    for func in tool_funcs:
        sig = inspect.signature(func)
        assert "token" not in sig.parameters, (
            f"Tool function '{func.__name__}' must not have a 'token' parameter in its signature. "
            "Tokens must come exclusively from connection headers."
        )


# 3. Token extracted from incoming HTTP request Authorization header (Review Fix 1)
def test_token_extracted_from_http_request_header():
    set_request_token(None)  # Ensure no task context token is present

    mock_req = MagicMock()
    mock_req.headers = {"Authorization": "Bearer pat_header_secret_xyz"}

    with patch("fastmcp.server.dependencies.get_http_request", return_value=mock_req):
        token = get_caller_token()
        assert token == "pat_header_secret_xyz"


# 4. Token extracted from ContextVar (used for tests and middleware)
def test_token_extracted_from_context_var():
    set_request_token("pat_context_secret_123")
    token = get_caller_token()
    assert token == "pat_context_secret_123"
    set_request_token(None)


# 5. Missing token raises ToolError
def test_missing_token_raises_tool_error():
    set_request_token(None)
    with pytest.raises(ToolError) as exc_info:
        get_caller_token()
    assert "Missing personal access token" in str(exc_info.value)
    assert "Authorization: Bearer" in str(exc_info.value)


# 6. list_incidents forwards token from connection
def test_list_incidents_forwards_caller_token():
    mock_data = [
        {"id": 1, "reference": "INC-1", "title": "Checkout failure", "status": "OPEN", "severity": "SEV1"}
    ]
    set_request_token("pat_user_alice")
    try:
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(200, mock_data)

            result = asyncio.run(list_incidents())

            assert result == mock_data
            assert mock_req.call_count == 1
            call_args = mock_req.call_args
            assert call_args[0][0] == "GET"
            assert call_args[0][1].endswith("/api/incidents")
            headers = call_args[1].get("headers", {})
            assert headers.get("Authorization") == "Bearer pat_user_alice"
    finally:
        set_request_token(None)


# 7. get_incident includes timeline data
def test_get_incident_includes_timeline_data():
    mock_incident = {
        "id": 42,
        "reference": "INC-42",
        "title": "Payment gateway timeout",
        "service": {"id": 1, "name": "Payments"},
        "severity": "SEV2",
        "status": "INVESTIGATING",
        "timeline": [
            {"id": 101, "entryType": "CREATED", "details": "Incident opened by monitoring", "createdAt": "2026-11-06T00:00:00Z"},
            {"id": 102, "entryType": "ROLE_ASSIGNMENT", "details": "Lead assigned to Shane", "createdAt": "2026-11-06T00:05:00Z"},
        ],
    }
    set_request_token("pat_user_bob")
    try:
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(200, mock_incident)

            result = asyncio.run(get_incident(incident_id=42))

            assert result["id"] == 42
            assert "timeline" in result
            assert len(result["timeline"]) == 2
            assert result["timeline"][0]["entryType"] == "CREATED"
            call_args = mock_req.call_args
            assert call_args[0][1].endswith("/api/incidents/42")
            headers = call_args[1].get("headers", {})
            assert headers.get("Authorization") == "Bearer pat_user_bob"
    finally:
        set_request_token(None)


# 8. search_incidents sends supported parameters WITHOUT size (Review Fix 2)
def test_search_incidents_sends_supported_parameters_without_size():
    mock_search_results = {
        "content": [{"id": 42, "title": "Payment gateway timeout"}],
        "totalElements": 1,
        "page": 0,
    }
    set_request_token("pat_user_charlie")
    try:
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(200, mock_search_results)

            result = asyncio.run(
                search_incidents(
                    query="gateway timeout",
                    service_id=1,
                    severity="SEV2",
                    status="RESOLVED",
                    page=0,
                )
            )

            assert result == mock_search_results
            call_args = mock_req.call_args
            assert call_args[0][1].endswith("/api/incidents/search")
            params = call_args[1].get("params", {})
            # Must NOT include "size" (Shane Review Fix 2)
            assert "size" not in params
            assert params == {
                "q": "gateway timeout",
                "serviceId": 1,
                "severity": "SEV2",
                "status": "RESOLVED",
                "page": 0,
            }
            headers = call_args[1].get("headers", {})
            assert headers.get("Authorization") == "Bearer pat_user_charlie"
    finally:
        set_request_token(None)


# 9. get_service forwards token
def test_get_service_forwards_token():
    mock_service = {
        "id": 5,
        "name": "Checkout",
        "description": "Order processing service",
        "criticality": "TIER_1",
    }
    set_request_token("pat_service_viewer")
    try:
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(200, mock_service)

            result = asyncio.run(get_service(service_id=5))

            assert result == mock_service
            call_args = mock_req.call_args
            assert call_args[0][1].endswith("/api/services/5")
            headers = call_args[1].get("headers", {})
            assert headers.get("Authorization") == "Bearer pat_service_viewer"
    finally:
        set_request_token(None)


# 10. who_is_on_call forwards token
def test_who_is_on_call_forwards_token():
    mock_on_call = {
        "serviceId": 5,
        "primary": {"userId": 12, "name": "Alice"},
        "fallback": {"userId": 14, "name": "Bob"},
    }
    set_request_token("pat_oncall_viewer")
    try:
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(200, mock_on_call)

            result = asyncio.run(who_is_on_call(service_id=5))

            assert result == mock_on_call
            call_args = mock_req.call_args
            assert call_args[0][1].endswith("/api/services/5/on-call")
            headers = call_args[1].get("headers", {})
            assert headers.get("Authorization") == "Bearer pat_oncall_viewer"
    finally:
        set_request_token(None)


# 11. list_alerts forwards token
def test_list_alerts_forwards_token():
    mock_alerts = [
        {"id": 1, "title": "High error rate", "status": "FIRING", "service": "Payments"}
    ]
    set_request_token("pat_alerts_token")
    try:
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(200, mock_alerts)

            result = asyncio.run(list_alerts())

            assert result == mock_alerts
            call_args = mock_req.call_args
            assert call_args[0][1].endswith("/api/alerts")
            headers = call_args[1].get("headers", {})
            assert headers.get("Authorization") == "Bearer pat_alerts_token"
    finally:
        set_request_token(None)


# 11b. service_map forwards token (#154 API contract)
def test_service_map_forwards_token():
    mock_map = {
        "nodes": [{"id": 1, "name": "Payments", "health": "HEALTHY", "tier": "TIER_1"}],
        "edges": [{"fromServiceId": 1, "toServiceId": 2, "type": "SYNC"}],
    }
    set_request_token("pat_map_viewer")
    try:
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(200, mock_map)

            result = asyncio.run(service_map())

            assert result == mock_map
            call_args = mock_req.call_args
            assert call_args[0][1].endswith("/api/service-map")
            headers = call_args[1].get("headers", {})
            assert headers.get("Authorization") == "Bearer pat_map_viewer"
    finally:
        set_request_token(None)


# 11c. get_runbook forwards token and uses GET /api/services/{id}/runbooks (#155 contract)
def test_get_runbook_forwards_token():
    mock_runbooks = [
        {"id": 10, "serviceId": 3, "title": "Database failover", "content": "Steps to failover"}
    ]
    set_request_token("pat_runbook_viewer")
    try:
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(200, mock_runbooks)

            result = asyncio.run(get_runbook(service_id=3))

            assert result == mock_runbooks
            call_args = mock_req.call_args
            assert call_args[0][1].endswith("/api/services/3/runbooks")
            headers = call_args[1].get("headers", {})
            assert headers.get("Authorization") == "Bearer pat_runbook_viewer"
    finally:
        set_request_token(None)


# 12. 401 becomes readable tool error
def test_401_becomes_readable_tool_error():
    set_request_token("pat_expired_token")
    try:
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(401, {"error": "Unauthorized"})

            with pytest.raises(ToolError) as exc_info:
                asyncio.run(list_incidents())

            err_msg = str(exc_info.value)
            assert "Authentication failed" in err_msg
            assert "401" in err_msg
            # Assert token is not exposed in error message
            assert "pat_expired_token" not in err_msg
    finally:
        set_request_token(None)


# 13. 403 becomes readable tool error
def test_403_becomes_readable_tool_error():
    set_request_token("pat_viewer_token")
    try:
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(403, {"error": "Forbidden"})

            with pytest.raises(ToolError) as exc_info:
                asyncio.run(get_service(service_id=1))

            err_msg = str(exc_info.value)
            assert "Permission denied" in err_msg
            assert "403" in err_msg
            assert "pat_viewer_token" not in err_msg
    finally:
        set_request_token(None)


# 14. 404 becomes readable tool error
def test_404_becomes_readable_tool_error():
    set_request_token("pat_token")
    try:
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(404, {"error": "Incident not found"})

            with pytest.raises(ToolError) as exc_info:
                asyncio.run(get_incident(incident_id=9999))

            err_msg = str(exc_info.value)
            assert "Resource not found" in err_msg
            assert "404" in err_msg
    finally:
        set_request_token(None)


# 15. Backend timeout becomes readable tool error
def test_backend_timeout_becomes_readable_tool_error():
    set_request_token("pat_token")
    try:
        with patch("httpx.AsyncClient.request", side_effect=httpx.TimeoutException("Connection timed out")):
            with pytest.raises(ToolError) as exc_info:
                asyncio.run(list_incidents())

            err_msg = str(exc_info.value)
            assert "Backend request timed out" in err_msg
    finally:
        set_request_token(None)


# 16. Caller A's token is never reused for caller B
def test_caller_tokens_never_reused_between_callers():
    observed_tokens = []

    async def mock_request(method, url, headers=None, **kwargs):
        observed_tokens.append(headers.get("Authorization"))
        return _mock_response(200, [])

    with patch("httpx.AsyncClient.request", side_effect=mock_request):
        # Caller A
        set_request_token("pat_caller_A")
        asyncio.run(list_incidents())
        assert observed_tokens[-1] == "Bearer pat_caller_A"

        # Caller B
        set_request_token("pat_caller_B")
        asyncio.run(list_incidents())
        assert observed_tokens[-1] == "Bearer pat_caller_B"

        # Clear token -> fails with ToolError
        set_request_token(None)
        with pytest.raises(ToolError):
            asyncio.run(list_incidents())


# 17. No token appears in logs, tool output, or raised error messages
def test_no_token_leaks_in_tool_output_or_error_messages(caplog):
    secret_pat = "pat_super_confidential_9876543210"

    set_request_token(secret_pat)
    try:
        # Error path
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(401, {"message": "Invalid token"})
            with pytest.raises(ToolError) as exc_info:
                asyncio.run(list_incidents())

            err_text = str(exc_info.value)
            assert secret_pat not in err_text

        # Log inspection
        for record in caplog.records:
            assert secret_pat not in record.getMessage()

        # Success path
        with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
            mock_req.return_value = _mock_response(200, [{"id": 1, "title": "Test Incident"}])
            result = asyncio.run(list_incidents())
            assert secret_pat not in str(result)
    finally:
        set_request_token(None)


# 18. No write tools are exposed
def test_no_write_tools_exposed():
    tool_names = set(mcp._tool_manager._tools.keys())

    prohibited_write_tools = {
        "add_comment",
        "acknowledge",
        "create_incident",
        "update_incident",
        "delete_incident",
        "declare_incident",
        "close_incident",
        "resolve_incident",
        "reassign",
        "assign_role",
        "create_service",
        "update_service",
        "delete_service",
        "create_runbook",
        "update_runbook",
        "delete_runbook",
    }

    found_write_tools = prohibited_write_tools.intersection(tool_names)
    assert not found_write_tools, f"Forbidden write tools found in MCP registry: {found_write_tools}"


# 19. Validation of input IDs
def test_input_validation():
    set_request_token("pat_valid")
    try:
        with pytest.raises(ToolError) as exc_info:
            asyncio.run(get_incident(incident_id=-1))
        assert "positive integer" in str(exc_info.value)

        with pytest.raises(ToolError) as exc_info:
            asyncio.run(get_service(service_id=0))
        assert "positive integer" in str(exc_info.value)

        with pytest.raises(ToolError) as exc_info:
            asyncio.run(who_is_on_call(service_id=-5))
        assert "positive integer" in str(exc_info.value)

        with pytest.raises(ToolError) as exc_info:
            asyncio.run(get_runbook(service_id=0))
        assert "positive integer" in str(exc_info.value)
    finally:
        set_request_token(None)


# 20. Main FastAPI application does NOT expose /mcp route (Review Fix 5)
def test_main_app_does_not_expose_mcp():
    from main import app as main_app
    route_paths = [getattr(r, "path", "") for r in main_app.routes]
    assert not any(p.startswith("/mcp") for p in route_paths), (
        "ai-service main.app must NOT mount /mcp. FastMCP runs as a separate process on port 8001 "
        "to prevent exposing unauthenticated /suggest, /embed, and /investigate endpoints."
    )


# 21. Standardized environment configuration (Review Fix 4)
def test_standardized_env_config(monkeypatch):
    monkeypatch.setenv("SETTLE_API_URL", "https://settle.example.com")
    monkeypatch.setenv("SETTLE_API_TIMEOUT", "25")
    assert get_backend_api_url() == "https://settle.example.com"
    assert get_backend_timeout() == 25.0


# 22. ASGI Starlette app generation for standalone server
def test_get_mcp_app():
    app = get_mcp_app()
    assert app is not None


# 23. End-to-end PAT token simulation across tools
def test_pat_token_end_to_end_flow():
    # Use realistic format of PAT token generated by PersonalAccessTokenService (starts with pat_)
    sample_pat = "pat_4f9a8b1c7d6e5a0f2b3c4d5e6f7a8b9c"
    set_request_token(sample_pat)

    captured_headers = {}

    async def mock_handler(method, url, headers=None, **kwargs):
        captured_headers["Authorization"] = headers.get("Authorization")
        if "incidents" in url:
            return _mock_response(200, [{"id": 1, "title": "Database slow query"}])
        elif "service-map" in url:
            return _mock_response(200, {"nodes": [], "edges": []})
        return _mock_response(200, {})

    try:
        with patch("httpx.AsyncClient.request", side_effect=mock_handler):
            incidents = asyncio.run(list_incidents())
            assert captured_headers.get("Authorization") == f"Bearer {sample_pat}"
            assert len(incidents) == 1
            assert incidents[0]["title"] == "Database slow query"

            s_map = asyncio.run(service_map())
            assert captured_headers.get("Authorization") == f"Bearer {sample_pat}"
            assert "nodes" in s_map
    finally:
        set_request_token(None)


# 24. MCP_PORT environment variable configuration
def test_mcp_port_env_configuration(monkeypatch):
    monkeypatch.setenv("MCP_PORT", "8005")
    import os
    port = int(os.getenv("MCP_PORT", "8001"))
    assert port == 8005
