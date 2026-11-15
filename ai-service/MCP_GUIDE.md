# Settle MCP Server Setup Guide

Connect **Claude Desktop**, **Cursor**, or **Claude Code** to the Settle Incident Management System using the Model Context Protocol (MCP) Streamable HTTP transport.

The MCP server runs as a standalone process (port 8001) as an authenticated thin adapter calling Settle's backend REST API. It executes operations strictly on behalf of the authenticated caller using their **personal access token (PAT)** passed via HTTP headers.

> [!IMPORTANT]
> **Process Isolation:** The MCP server runs as its own standalone process on port 8001 rather than being mounted on `ai-service` (port 8000). This guarantees that internal AI endpoints (`/suggest`, `/embed`, `/investigate`) remain accessible only from the backend and are never exposed to editors or external callers.
>
> **Draft Status (#151):** Personal access tokens (`pat_...`) are being implemented in the backend under Issue #151. This pull request remains a draft until #151 merges.

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

Run the MCP server as a dedicated standalone service:

```powershell
cd ai-service
python mcp_server.py
```
The Streamable HTTP MCP endpoint is accessible at:
```
http://localhost:8001/mcp
```

### Environment Configuration

| Variable | Default | Purpose |
|---|---|---|
| `MCP_PORT` | `8001` | Port for the standalone FastMCP Streamable HTTP server |
| `SETTLE_API_URL` | `http://localhost:8080` | Settle backend REST API base URL |
| `SETTLE_API_TIMEOUT` | `10` | Timeout in seconds for backend calls |

Configure these in `.env` (or pass as environment variables). Placeholders are in `.env.example`.

---

## 3. Client Configuration

Authentication is passed exclusively via connection headers (`Authorization: Bearer <pat_token>`). Tokens are **never** passed as tool arguments, ensuring credentials never appear in model transcripts, prompt logs, or editor tool logs.

### A. Claude Desktop

Add the server to your `claude_desktop_config.json`:
- **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "settle": {
      "url": "http://localhost:8001/mcp",
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
   - **URL:** `http://localhost:8001/mcp`
   - **Headers:**
     - Key: `Authorization`
     - Value: `Bearer pat_YOUR_PERSONAL_ACCESS_TOKEN`
5. Click **Save** and verify the indicator turns green.

---

### C. Claude Code

When starting or configuring Claude Code:

```bash
claude mcp add --transport http settle http://localhost:8001/mcp --header "Authorization: Bearer pat_YOUR_PERSONAL_ACCESS_TOKEN"
```

Or configure via `.claude.json`:
```json
{
  "mcpServers": {
    "settle": {
      "url": "http://localhost:8001/mcp",
      "headers": {
        "Authorization": "Bearer pat_YOUR_PERSONAL_ACCESS_TOKEN"
      }
    }
  }
}
```

---

## 4. Available Tools

### Read Tools

| Tool | Parameters | Description | Backend API |
|---|---|---|---|
| `list_incidents` | none | List all incidents, newest first | `GET /api/incidents` |
| `get_incident` | `incident_id` (int) | Incident details with full timeline | `GET /api/incidents/{id}` |
| `search_incidents` | `query`, `service_id`, `severity`, `status`, `page` | Full-text and filter search of past incidents (fixed 20/page) | `GET /api/incidents/search` |
| `get_service` | `service_id` (int) | Service owner, criticality, and SLA targets | `GET /api/services/{id}` |
| `who_is_on_call` | `service_id` (int) | Current primary on-call and fallback contact | `GET /api/services/{id}/on-call` |
| `list_alerts` | none | Inbox alerts from monitoring integrations | `GET /api/alerts` |
| `service_map` | none | Service dependency graph and health status | `GET /api/service-map` |
| `get_runbook` | `service_id` (int) | Service runbooks and mitigation steps | `GET /api/services/{id}/runbooks` |

### Write Tools (Sprint 5, Issue #248)

| Tool | Parameters | Description | Backend API | Minimum Role |
|---|---|---|---|---|
| `add_comment` | `incident_id` (int), `comment` (str, 1–5000 chars) | Add a comment to an incident timeline. Shows **via MCP** attribution in the timeline and audit log. | `POST /api/incidents/{id}/comments` | ENGINEER |
| `acknowledge` | `incident_id` (int) | Acknowledge an OPEN incident. Automatically retrieves the current incident version first to prevent stale acknowledgements. Shows **via MCP** attribution. | `POST /api/incidents/{id}/acknowledge` | ENGINEER |

> [!IMPORTANT]
> **Write Tool Security Rules:**
> - Both write tools use the caller's personal access token exclusively. The token is never accepted as a tool argument.
> - `acknowledge` automatically reads the current `version` from `GET /api/incidents/{id}` before submitting. The version is never hardcoded.
> - A HTTP 409 conflict (concurrent modification) returns a readable retry message.
> - A HTTP 403 (Viewer role) returns a readable permission-denied message.
> - Tokens are never logged or exposed in error messages.

> [!NOTE]
> **Permanently prohibited write operations (will never be added):**
> `declare_incident`, `close_incident`, `resolve_incident`, `update_incident`, `delete_incident`,
> `reassign`, `assign_role`, `create_service`, `update_service`, `delete_service`,
> `create_runbook`, `update_runbook`, `delete_runbook`.
> These operations require UI-level confirmation or multi-step workflows inappropriate for a text channel.

---

## 5. Verification & Testing

Verify that your client can communicate with the MCP server:

1. **Ask Claude or Cursor:**
   - *"List the active incidents in Settle."*
   - *"Who is currently on call for the Payments service?"*
   - *"Search past incidents for payment gateway timeouts."*
   - *"Add a comment to incident 42: Investigating database pool exhaustion."*
   - *"Acknowledge incident 42."*
2. **Observe Tool Execution:**
   - The client will invoke `list_incidents`, `who_is_on_call(service_id=1)`, `search_incidents(query="payment gateway timeout")`, `add_comment(incident_id=42, comment="Investigating database pool exhaustion.")`, or `acknowledge(incident_id=42)`.
   - Results are returned in structured format based on your access permissions in Settle.
   - Comments and acknowledgements made via MCP show **via MCP** in the incident timeline and audit log.

---

## 6. Troubleshooting

- **HTTP 401 Authentication Error:**
  - Verify your token starts with `pat_` and is not expired or revoked.
  - Verify the header format in your client config: `Authorization: Bearer pat_...`.
- **HTTP 403 Permission Denied:**
  - Your user role (e.g., Viewer vs. Engineer) determines which endpoints you can access.
  - Viewers can read incidents but cannot add comments or acknowledge (ENGINEER or ADMIN required).
- **HTTP 409 Conflict (acknowledge):**
  - Another user or operation modified the incident between the version read and the acknowledgement submit.
  - The tool returns a readable message: re-read the incident and retry.
- **Connection Refused / Failed to Connect:**
  - Verify the standalone MCP server is running on `http://localhost:8001`: `python mcp_server.py`.
  - Verify `SETTLE_API_URL` points to an active Settle backend instance (default: `http://localhost:8080`).
