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
claude mcp add settle http://localhost:8001/mcp --header "Authorization=Bearer pat_YOUR_PERSONAL_ACCESS_TOKEN"
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

## 4. Available Read Tools

| Tool | Parameters | Description | Backend API |
|---|---|---|---|
| `list_incidents` | none | List all incidents, newest first | `GET /api/incidents` |
| `get_incident` | `incident_id` (int) | Incident details with full timeline | `GET /api/incidents/{id}` |
| `search_incidents` | `query`, `service_id`, `severity`, `status`, `page` | Full-text and filter search of past incidents (fixed 20/page) | `GET /api/incidents/search` |
| `get_service` | `service_id` (int) | Service owner, criticality, and SLA targets | `GET /api/services/{id}` |
| `who_is_on_call` | `service_id` (int) | Current primary on-call and fallback contact | `GET /api/services/{id}/on-call` |
| `list_alerts` | none | Inbox alerts from monitoring integrations | `GET /api/alerts` |

> [!NOTE]
> - All write tools (e.g. `add_comment`, `acknowledge`, `declare_incident`) are deferred to Sprint 5 (Decision D38).
> - Additional roadmap read tools (`service_map` for #154 and `get_runbook` for #155) will be registered once their backend PRs merge.

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
  - Your user role (e.g., Viewer vs. Engineer) determines which endpoints you can access.
- **Connection Refused / Failed to Connect:**
  - Verify the standalone MCP server is running on `http://localhost:8001`: `python mcp_server.py`.
  - Verify `SETTLE_API_URL` points to an active Settle backend instance (default: `http://localhost:8080`).
