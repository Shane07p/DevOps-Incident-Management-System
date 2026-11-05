# Settle MCP Server Setup Guide

Connect **Claude Desktop**, **Cursor**, or **Claude Code** to the Settle Incident Management System using the Model Context Protocol (MCP) Streamable HTTP transport.

The MCP server runs inside `ai-service` as an authenticated thin adapter calling Settle's backend REST API. It executes operations strictly on behalf of the authenticated caller using their **personal access token (PAT)**.

---

## 1. Prerequisites

1. **Settle Backend Running:**
   By default, the backend runs on `http://localhost:8080`.
2. **Personal Access Token (PAT):**
   Generate a personal access token in Settle under your user profile (`POST /api/me/tokens` or via the web UI). Your token will resemble `pat_<alphanumeric_string>`.
3. **Python 3.12+ with dependencies installed:**
   ```powershell
   cd ai-service
   pip install -r requirements.txt
   ```

---

## 2. Starting the MCP Server

You can run the MCP server embedded inside `ai-service` or as a standalone process:

### Option A: Embedded with `ai-service` (Recommended)
```powershell
cd ai-service
python -m uvicorn main:app --host 0.0.0.0 --port 8000
```
The Streamable HTTP MCP endpoint is accessible at:
```
http://localhost:8000/mcp
```

### Option B: Standalone MCP Server
```powershell
cd ai-service
python mcp_server.py
```
The standalone server runs on `http://localhost:8001/mcp`.

### Environment Configuration (Optional)
| Variable | Default | Purpose |
|---|---|---|
| `SETTLE_API_URL` | `http://localhost:8080` | Settle backend REST API base URL |
| `SETTLE_API_TIMEOUT` | `10` | Timeout in seconds for backend calls |

---

## 3. Client Configuration

### A. Claude Desktop

Add the server to your `claude_desktop_config.json`:
- **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "settle": {
      "url": "http://localhost:8000/mcp",
      "headers": {
        "Authorization": "Bearer pat_YOUR_PERSONAL_ACCESS_TOKEN"
      }
    }
  }
}
```

Restart Claude Desktop after updating the configuration.

---

### B. Cursor

In Cursor:
1. Open **Settings** (`Ctrl+,` or `Cmd+,`).
2. Navigate to **Features** → **MCP Servers**.
3. Click **Add New MCP Server**.
4. Configure the server:
   - **Name:** `settle`
   - **Type:** `sse` or `http`
   - **URL:** `http://localhost:8000/mcp`
   - **Headers:**
     - Key: `Authorization`
     - Value: `Bearer pat_YOUR_PERSONAL_ACCESS_TOKEN`
5. Click **Save** and verify the indicator turns green.

---

### C. Claude Code

When starting or configuring Claude Code:

```bash
claude mcp add settle http://localhost:8000/mcp --header "Authorization=Bearer pat_YOUR_PERSONAL_ACCESS_TOKEN"
```

Or configure via `.claude.json`:
```json
{
  "mcpServers": {
    "settle": {
      "url": "http://localhost:8000/mcp",
      "headers": {
        "Authorization": "Bearer pat_YOUR_PERSONAL_ACCESS_TOKEN"
      }
    }
  }
}
```

---

## 4. Available Read Tools

| Tool | Parameters | Description | Backend API |
|---|---|---|---|
| `list_incidents` | none | List all incidents, newest first | `GET /api/incidents` |
| `get_incident` | `incident_id` (int) | Incident details with full timeline | `GET /api/incidents/{id}` |
| `search_incidents` | `query`, `service_id`, `severity`, `status`, `page`, `size` | Full-text and filter search of past incidents | `GET /api/incidents/search` |
| `get_service` | `service_id` (int) | Service owner, criticality, and SLA targets | `GET /api/services/{id}` |
| `who_is_on_call` | `service_id` (int) | Current primary on-call and fallback contact | `GET /api/services/{id}/on-call` |
| `list_alerts` | none | Inbox alerts from monitoring integrations | `GET /api/alerts` |
| `service_map` | none | Service dependency graph and health | `GET /api/service-map` |
| `get_runbook` | `service_id` or `runbook_id` | Service troubleshooting runbook steps | `GET /api/services/{id}/runbooks` |

> [!NOTE]
> All write tools (e.g. `add_comment`, `acknowledge`) are deferred to Sprint 5 (Decision D38). Incident state and severity mutations are never permitted via MCP.

---

## 5. Verification & Testing

Verify that your client can communicate with the MCP server:

1. **Ask Claude or Cursor:**
   - *"List the active incidents in Settle."*
   - *"Who is currently on call for the Payments service?"*
   - *"Search past incidents for payment gateway timeouts."*
2. **Observe Tool Execution:**
   - The client will invoke `list_incidents`, `who_is_on_call(service_id=1)`, or `search_incidents(query="payment gateway timeout")`.
   - Results are returned in structured format based on your access permissions in Settle.

---

## 6. Troubleshooting

- **HTTP 401 Authentication Error:**
  - Verify your token starts with `pat_` and is not expired or revoked.
  - Verify the header format in your client config: `Authorization: Bearer pat_...`.
- **HTTP 403 Permission Denied:**
  - Your user role (e.g., Viewer vs. Engineer) determines which endpoints you can access. Verify your organization role in Settle.
- **Connection Refused / Failed to Connect:**
  - Verify `ai-service` is running on `http://localhost:8000`.
  - Verify `SETTLE_API_URL` points to an active Settle backend instance (default: `http://localhost:8080`).
- **Endpoint 404 for Roadmap Tools (`service_map`, `get_runbook`):**
  - These tools connect to backend APIs being developed in Sprint 4 (#154, #155). If the backend PR has not yet been merged, the tool returns a clean 404 notification rather than crashing.
