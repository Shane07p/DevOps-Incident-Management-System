"""
tests/test_mcp_server.py
-------------------------
Comprehensive test suite for Settle FastMCP Streamable HTTP server with read tools (Sprint 4, Issue #162).

Covers all 16 acceptance & test criteria:
  1. Each registered MCP tool exists with expected name.
  2. list_incidents forwards caller's token.
  3. get_incident includes timeline data.
  4. search_incidents sends supported search parameters.
  5. get_service forwards token.
  6. who_is_on_call forwards token.
  7. list_alerts forwards token.
  8. Optional tools work where backend APIs exist and fail cleanly when missing.
  9. Each tool returns a correctly shaped structured result.
  10. 401 becomes a readable tool error.
  11. 403 becomes a readable tool error.
  12. 404 becomes a readable tool error.
  13. Backend timeout becomes a readable tool error.
  14. Caller A's token is never reused for caller B.
  15. No token appears in logs, tool output, or raised error messages.
  16. No write tools are exposed.
"""

import asyncio
from unittest.mock import AsyncMock, patch

from fastmcp.exceptions import ToolError
import httpx
import pytest

from mcp_server import (
    _call_backend_api,
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


# 1. Registered tools check
def test_each_registered_mcp_tool_exists_with_expected_name():
    tool_names = set(mcp._tool_manager._tools.keys())
    expected_core_tools = {
        "list_incidents",
        "get_incident",
        "search_incidents",
        "get_service",
        "who_is_on_call",
        "list_alerts",
    }
    expected_optional_tools = {
        "service_map",
        "get_runbook",
    }
    for expected in expected_core_tools | expected_optional_tools:
        assert expected in tool_names, f"Expected tool '{expected}' not registered in FastMCP"


# 2. list_incidents forwards token
def test_list_incidents_forwards_caller_token():
    mock_data = [
        {"id": 1, "reference": "INC-1", "title": "Checkout failure", "status": "OPEN", "severity": "SEV1"}
    ]
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(200, mock_data)

        result = asyncio.run(list_incidents(token="pat_user_alice"))

        assert result == mock_data
        assert mock_req.call_count == 1
        call_args = mock_req.call_args
        assert call_args[0][0] == "GET"
        assert call_args[0][1].endswith("/api/incidents")
        headers = call_args[1].get("headers", {})
        assert headers.get("Authorization") == "Bearer pat_user_alice"


# 3. get_incident includes timeline data
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
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(200, mock_incident)

        result = asyncio.run(get_incident(incident_id=42, token="pat_user_bob"))

        assert result["id"] == 42
        assert "timeline" in result
        assert len(result["timeline"]) == 2
        assert result["timeline"][0]["entryType"] == "CREATED"
        call_args = mock_req.call_args
        assert call_args[0][1].endswith("/api/incidents/42")
        headers = call_args[1].get("headers", {})
        assert headers.get("Authorization") == "Bearer pat_user_bob"


# 4. search_incidents sends supported search parameters
def test_search_incidents_sends_supported_search_parameters():
    mock_search_results = {
        "content": [{"id": 42, "title": "Payment gateway timeout"}],
        "totalElements": 1,
        "page": 0,
        "size": 20,
    }
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(200, mock_search_results)

        result = asyncio.run(
            search_incidents(
                query="gateway timeout",
                service_id=1,
                severity="SEV2",
                status="RESOLVED",
                page=0,
                size=10,
                token="pat_user_charlie",
            )
        )

        assert result == mock_search_results
        call_args = mock_req.call_args
        assert call_args[0][1].endswith("/api/incidents/search")
        params = call_args[1].get("params", {})
        assert params == {
            "q": "gateway timeout",
            "serviceId": 1,
            "severity": "SEV2",
            "status": "RESOLVED",
            "page": 0,
            "size": 10,
        }
        headers = call_args[1].get("headers", {})
        assert headers.get("Authorization") == "Bearer pat_user_charlie"


# 5. get_service forwards token
def test_get_service_forwards_token():
    mock_service = {
        "id": 5,
        "name": "Checkout",
        "description": "Order processing service",
        "criticality": "TIER_1",
    }
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(200, mock_service)

        result = asyncio.run(get_service(service_id=5, token="pat_service_viewer"))

        assert result == mock_service
        call_args = mock_req.call_args
        assert call_args[0][1].endswith("/api/services/5")
        headers = call_args[1].get("headers", {})
        assert headers.get("Authorization") == "Bearer pat_service_viewer"


# 6. who_is_on_call forwards token
def test_who_is_on_call_forwards_token():
    mock_on_call = {
        "serviceId": 5,
        "primary": {"userId": 12, "name": "Alice"},
        "fallback": {"userId": 14, "name": "Bob"},
    }
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(200, mock_on_call)

        result = asyncio.run(who_is_on_call(service_id=5, token="pat_oncall_viewer"))

        assert result == mock_on_call
        call_args = mock_req.call_args
        assert call_args[0][1].endswith("/api/services/5/on-call")
        headers = call_args[1].get("headers", {})
        assert headers.get("Authorization") == "Bearer pat_oncall_viewer"


# 7. list_alerts forwards token
def test_list_alerts_forwards_token():
    mock_alerts = [
        {"id": 1, "title": "High error rate", "status": "FIRING", "service": "Payments"}
    ]
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(200, mock_alerts)

        result = asyncio.run(list_alerts(token="pat_alerts_token"))

        assert result == mock_alerts
        call_args = mock_req.call_args
        assert call_args[0][1].endswith("/api/alerts")
        headers = call_args[1].get("headers", {})
        assert headers.get("Authorization") == "Bearer pat_alerts_token"


# 8. Optional tools work where backend APIs exist and fail cleanly when missing
def test_optional_tools_work_where_api_exists_and_fail_cleanly_when_missing():
    # When service-map API exists (200 OK)
    mock_map = {"nodes": [{"id": 1, "name": "Payments"}], "edges": []}
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(200, mock_map)
        res = asyncio.run(service_map(token="pat_map"))
        assert res == mock_map

    # When service-map API does not exist (404 Not Found)
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(404, {"error": "Not Found"})
        with pytest.raises(ToolError) as exc_info:
            asyncio.run(service_map(token="pat_map"))
        assert "404" in str(exc_info.value)
        assert "api/service-map" in str(exc_info.value)

    # When runbooks API exists
    mock_runbook = {"id": 1, "serviceId": 2, "title": "Rollback procedure", "steps": ["Step 1", "Step 2"]}
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(200, mock_runbook)
        res = asyncio.run(get_runbook(service_id=2, token="pat_runbook"))
        assert res == mock_runbook

    # When runbooks API does not exist (404 Not Found)
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(404, {"error": "Not Found"})
        with pytest.raises(ToolError) as exc_info:
            asyncio.run(get_runbook(service_id=2, token="pat_runbook"))
        assert "404" in str(exc_info.value)


# 9. Correctly shaped structured results
def test_tools_return_correctly_shaped_structured_results():
    sample_incident = {"id": 10, "reference": "INC-10", "title": "Disk Full"}
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(200, sample_incident)
        res = asyncio.run(get_incident(incident_id=10, token="pat_shape"))
        assert isinstance(res, dict)
        assert res["id"] == 10
        assert res["reference"] == "INC-10"


# 10. 401 becomes readable tool error
def test_401_becomes_readable_tool_error():
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(401, {"error": "Unauthorized"})

        with pytest.raises(ToolError) as exc_info:
            asyncio.run(list_incidents(token="pat_expired_token"))

        err_msg = str(exc_info.value)
        assert "Authentication failed" in err_msg
        assert "401" in err_msg
        # Assert token is not exposed in error message
        assert "pat_expired_token" not in err_msg


# 11. 403 becomes readable tool error
def test_403_becomes_readable_tool_error():
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(403, {"error": "Forbidden"})

        with pytest.raises(ToolError) as exc_info:
            asyncio.run(get_service(service_id=1, token="pat_viewer_token"))

        err_msg = str(exc_info.value)
        assert "Permission denied" in err_msg
        assert "403" in err_msg
        assert "pat_viewer_token" not in err_msg


# 12. 404 becomes readable tool error
def test_404_becomes_readable_tool_error():
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(404, {"error": "Incident not found"})

        with pytest.raises(ToolError) as exc_info:
            asyncio.run(get_incident(incident_id=9999, token="pat_token"))

        err_msg = str(exc_info.value)
        assert "Resource not found" in err_msg
        assert "404" in err_msg


# 13. Backend timeout becomes readable tool error
def test_backend_timeout_becomes_readable_tool_error():
    with patch("httpx.AsyncClient.request", side_effect=httpx.TimeoutException("Connection timed out")):
        with pytest.raises(ToolError) as exc_info:
            asyncio.run(list_incidents(token="pat_token"))

        err_msg = str(exc_info.value)
        assert "Backend request timed out" in err_msg


# 14. Caller A's token is never reused for caller B
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

        # Explicit parameter override
        set_request_token("pat_caller_A")
        asyncio.run(list_incidents(token="pat_caller_C"))
        assert observed_tokens[-1] == "Bearer pat_caller_C"

        # Clear token
        set_request_token(None)
        with pytest.raises(ToolError):
            asyncio.run(list_incidents())


# 15. No token appears in logs, tool output, or raised error messages
def test_no_token_leaks_in_tool_output_or_error_messages(caplog):
    secret_pat = "pat_super_confidential_9876543210"

    # Test error path
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(401, {"message": "Invalid token"})
        with pytest.raises(ToolError) as exc_info:
            asyncio.run(list_incidents(token=secret_pat))

        err_text = str(exc_info.value)
        assert secret_pat not in err_text

    # Test log inspection
    for record in caplog.records:
        assert secret_pat not in record.getMessage()

    # Test success path
    with patch("httpx.AsyncClient.request", new_callable=AsyncMock) as mock_req:
        mock_req.return_value = _mock_response(200, [{"id": 1, "title": "Test Incident"}])
        result = asyncio.run(list_incidents(token=secret_pat))
        assert secret_pat not in str(result)


# 16. No write tools are exposed
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


# 17. Validation of inputs
def test_input_validation():
    with pytest.raises(ToolError) as exc_info:
        asyncio.run(get_incident(incident_id=-1, token="pat_valid"))
    assert "positive integer" in str(exc_info.value)

    with pytest.raises(ToolError) as exc_info:
        asyncio.run(get_service(service_id=0, token="pat_valid"))
    assert "positive integer" in str(exc_info.value)

    with pytest.raises(ToolError) as exc_info:
        asyncio.run(who_is_on_call(service_id=-5, token="pat_valid"))
    assert "positive integer" in str(exc_info.value)

    with pytest.raises(ToolError) as exc_info:
        asyncio.run(get_runbook(token="pat_valid"))
    assert "Either service_id or runbook_id must be provided" in str(exc_info.value)


# 18. ASGI Starlette app generation
def test_get_mcp_app():
    app = get_mcp_app()
    assert app is not None
    # Check routes
    routes = [r.path for r in app.routes]
    assert "/" in routes or "/mcp" in routes
