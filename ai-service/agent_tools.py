"""
agent_tools.py - Structured Evidence Tools and Tool Registry for Sprint 3 (Issue #91).
--------------------------------------------------------------------------------------
Provides registered, schema-validated tools operating on recorded Demo Shop data.
Integrates with existing Issue #92 log pattern evidence (log_patterns.py).
"""

from __future__ import annotations

import datetime
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional
from pydantic import BaseModel, ConfigDict, Field, alias_generators

from log_patterns import (
    find_log_patterns,
    load_recorded_logs,
    parse_time,
)

DATA_DIR = Path(__file__).resolve().parent / "data" / "fixtures"
DEFAULT_LOG_FIXTURE = DATA_DIR / "demo_shop_slow_gateway.jsonl"


# ---------------------------------------------------------------------------
# Data Models
# ---------------------------------------------------------------------------

class EvidenceItem(BaseModel):
    """Structured evidence item captured during an investigation."""
    model_config = ConfigDict(populate_by_name=True, alias_generator=alias_generators.to_camel)

    id: str = Field(..., description="Stable unique identifier for evidence item (e.g. ev-log-0)")
    source_tool: str = Field(..., description="Tool that yielded this evidence")
    evidence_type: str = Field(..., description="Category: log_pattern, deploy_event, metric_anomaly, etc.")
    content: str = Field(..., description="Human-readable factual observation")
    details: Dict[str, Any] = Field(default_factory=dict, description="Underlying machine-readable data")


class ToolResult(BaseModel):
    """Deterministic result of a tool execution."""
    tool_name: str
    success: bool
    data: Any = None
    evidence_items: List[EvidenceItem] = Field(default_factory=list)
    error: Optional[str] = None


class ToolDefinition:
    """Tool definition containing its name, description, schema, and callable."""

    def __init__(
        self,
        name: str,
        description: str,
        parameters_schema: Dict[str, Any],
        func: Callable[..., ToolResult],
    ):
        self.name = name
        self.description = description
        self.parameters_schema = parameters_schema
        self.func = func

    def to_schema(self) -> Dict[str, Any]:
        """Return OpenAI/Ollama tool definition structure."""
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters_schema,
            },
        }


# ---------------------------------------------------------------------------
# Concrete Demo Shop Evidence Tools
# ---------------------------------------------------------------------------

def query_log_patterns(
    service: str,
    incident_start: str,
    incident_end: str,
    query_base_url: str = "",
    datasource_uid: str = "",
) -> ToolResult:
    """
    Search recorded Demo Shop logs for new or spiking patterns using Issue #92 implementation.
    The log fixture path is determined internally; callers cannot override it.
    """
    target_path = DEFAULT_LOG_FIXTURE
    if not target_path.exists():
        return ToolResult(
            tool_name="query_log_patterns",
            success=False,
            error=f"Recorded log fixture not found at {target_path}",
        )

    try:
        logs = load_recorded_logs(target_path)
        patterns = find_log_patterns(
            logs=logs,
            service=service,
            incident_start=incident_start,
            incident_end=incident_end,
            query_base_url=query_base_url,
            datasource_uid=datasource_uid,
        )
    except Exception as exc:
        return ToolResult(
            tool_name="query_log_patterns",
            success=False,
            error=f"Log pattern analysis failed: {exc}",
        )

    evidence_items: List[EvidenceItem] = []
    for idx, p in enumerate(patterns):
        ev_id = f"ev-log-{idx}"
        status_desc = "is new (0 before)" if p["status"] == "new" else f"is spiking ({p['count_before']} before)"
        content = (
            f"Log pattern '{p['pattern']}' on '{service}' {status_desc} "
            f"with {p['count_after']} occurrences during the incident window"
        )
        if p.get("query_url"):
            content += f" (Explore: {p['query_url']})"

        evidence_items.append(
            EvidenceItem(
                id=ev_id,
                source_tool="query_log_patterns",
                evidence_type="log_pattern",
                content=content,
                details=p,
            )
        )

    return ToolResult(
        tool_name="query_log_patterns",
        success=True,
        data={"service": service, "patterns_found": len(patterns), "patterns": patterns},
        evidence_items=evidence_items,
    )


# Recorded Demo Shop deploys fixture matching Issue #76 / SCN-03
DEMO_SHOP_RECORDED_DEPLOYS = [
    {
        "service": "checkout",
        "version": "v1.0.1-broken",
        "previous_version": "v1.0.0",
        "timestamp": "2026-10-27T08:16:00Z",
        "commit": "a1b2c3d",
        "author": "ci-bot",
        "status": "completed",
        "message": "Deployed v1.0.1-broken to checkout service causing immediate checkout deadlock",
    },
    {
        "service": "payments",
        "version": "v1.0.0",
        "previous_version": "v0.9.9",
        "timestamp": "2026-10-26T12:00:00Z",
        "commit": "f4708c4",
        "author": "ci-bot",
        "status": "completed",
        "message": "Routine stable deployment to payments service",
    },
]


def query_deploy_events(
    service: str,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
) -> ToolResult:
    """
    Query recorded deployments for the target service within an optional time range.
    """
    try:
        start_dt = parse_time(start_time) if start_time else None
        end_dt = parse_time(end_time) if end_time else None
    except Exception as exc:
        return ToolResult(
            tool_name="query_deploy_events",
            success=False,
            error=f"Invalid timestamp format: {exc}",
        )

    matching_deploys = []
    for dep in DEMO_SHOP_RECORDED_DEPLOYS:
        if dep["service"].lower() != service.lower():
            continue
        if start_dt or end_dt:
            try:
                dep_dt = parse_time(dep["timestamp"])
            except Exception:
                continue
            if start_dt and dep_dt < start_dt:
                continue
            if end_dt and dep_dt > end_dt:
                continue
        matching_deploys.append(dep)

    evidence_items: List[EvidenceItem] = []
    for idx, dep in enumerate(matching_deploys):
        ev_id = f"ev-deploy-{idx}"
        content = (
            f"Deploy commit {dep['commit']} to '{dep['service']}' deployed version {dep['version']} "
            f"(prev: {dep['previous_version']}) at {dep['timestamp']} by {dep['author']}: {dep['message']}"
        )
        evidence_items.append(
            EvidenceItem(
                id=ev_id,
                source_tool="query_deploy_events",
                evidence_type="deploy_event",
                content=content,
                details=dep,
            )
        )

    return ToolResult(
        tool_name="query_deploy_events",
        success=True,
        data={"service": service, "deploys": matching_deploys},
        evidence_items=evidence_items,
    )


# Recorded Demo Shop metric baselines and anomalies matching the 5 fault scenarios (SCN-01 to SCN-05)
# Each observation carries when it was seen, inside its own scenario's window, so a query for one
# incident's time range returns that incident's evidence and not another scenario's. SCN-01's falls
# within its recording (demo_shop_slow_gateway.jsonl); the others stand apart until #142's recordings
# replace these hand-written values.
DEMO_SHOP_RECORDED_METRICS = {
    "payments": [
        {
            "metric": "demo_http_request_duration_seconds",
            "scenario_id": "SCN-01",
            "observed_at": "2026-10-27T08:15:40Z",
            "observation": "p99 latency 2001.93ms (normal baseline < 50ms, SLA threshold 1500ms)",
            "normal_value": 25.0,
            "incident_value": 2001.93,
            "unit": "ms",
            "severity": "CRITICAL",
        },
        {
            "metric": "demo_certificate_days_until_expiry",
            "scenario_id": "SCN-05",
            "observed_at": "2026-10-30T10:00:00Z",
            "observation": "TLS certificate expiry dropped to 0 days (normal baseline 90 days)",
            "normal_value": 90.0,
            "incident_value": 0.0,
            "unit": "days",
            "severity": "CRITICAL",
        },
        {
            "metric": "demo_ssl_errors_total",
            "scenario_id": "SCN-05",
            "observed_at": "2026-10-30T10:00:05Z",
            "observation": "SSL handshake errors counter incremented to 1 (normal baseline 0)",
            "normal_value": 0.0,
            "incident_value": 1.0,
            "unit": "errors",
            "severity": "HIGH",
        },
    ],
    "checkout": [
        {
            "metric": "demo_http_requests_total_5xx",
            "scenario_id": "SCN-02",
            "observed_at": "2026-10-28T10:00:00Z",
            "observation": "Checkout failure rate spiked to 80% with simulated database deadlocks (normal baseline < 0.1%)",
            "normal_value": 0.001,
            "incident_value": 0.80,
            "unit": "rate",
            "severity": "CRITICAL",
        },
    ],
    "order-fulfillment": [
        {
            "metric": "demo_queue_depth",
            "scenario_id": "SCN-04",
            "observed_at": "2026-10-29T10:00:00Z",
            "observation": "Order fulfillment queue worker paused, backlog accumulated to 42 orders (normal baseline 0-1)",
            "normal_value": 0.0,
            "incident_value": 42.0,
            "unit": "orders",
            "severity": "HIGH",
        },
    ],
}


def query_metric_anomalies(
    service: str,
    metric_name: Optional[str] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
) -> ToolResult:
    """
    Query recorded metric baselines vs anomalous values for the target service, observed within an
    optional time range, as query_deploy_events does.
    """
    try:
        start_dt = parse_time(start_time) if start_time else None
        end_dt = parse_time(end_time) if end_time else None
    except Exception as exc:
        return ToolResult(
            tool_name="query_metric_anomalies",
            success=False,
            error=f"Invalid timestamp format: {exc}",
        )

    service_key = service.lower()
    metrics = DEMO_SHOP_RECORDED_METRICS.get(service_key, [])
    if metric_name:
        metrics = [m for m in metrics if m["metric"].lower() == metric_name.lower()]
    if start_dt:
        metrics = [m for m in metrics if parse_time(m["observed_at"]) >= start_dt]
    if end_dt:
        metrics = [m for m in metrics if parse_time(m["observed_at"]) <= end_dt]

    evidence_items: List[EvidenceItem] = []
    for idx, m in enumerate(metrics):
        ev_id = f"ev-metric-{idx}"
        content = f"Metric anomaly on '{service}': {m['metric']} - {m['observation']}"
        evidence_items.append(
            EvidenceItem(
                id=ev_id,
                source_tool="query_metric_anomalies",
                evidence_type="metric_anomaly",
                content=content,
                details=m,
            )
        )

    return ToolResult(
        tool_name="query_metric_anomalies",
        success=True,
        data={"service": service, "anomalies": metrics},
        evidence_items=evidence_items,
    )


DEMO_SHOP_SERVICES = {
    "payments": {
        "status": "degraded",
        "description": "Payment authorization service",
        "dependencies": ["bank_gateway"],
        "dependents": ["checkout"],
    },
    "checkout": {
        "status": "degraded",
        "description": "Checkout and cart completion service",
        "dependencies": ["payments", "search", "order-fulfillment"],
        "dependents": [],
    },
    "search": {
        "status": "healthy",
        "description": "Product catalog search service",
        "dependencies": [],
        "dependents": ["checkout"],
    },
    "order-fulfillment": {
        "status": "degraded",
        "description": "Background order processing queue worker",
        "dependencies": [],
        "dependents": ["checkout"],
    },
}


def get_service_health(service: str) -> ToolResult:
    """
    Get recorded health status and dependency topology for a service.
    """
    info = DEMO_SHOP_SERVICES.get(service.lower())
    if not info:
        return ToolResult(
            tool_name="get_service_health",
            success=False,
            error=f"Service '{service}' not found in Demo Shop catalog.",
        )

    ev_id = "ev-health-0"
    content = f"Service '{service}' status is '{info['status']}'. Dependencies: {info['dependencies']}."
    evidence_items = [
        EvidenceItem(
            id=ev_id,
            source_tool="get_service_health",
            evidence_type="service_health",
            content=content,
            details=info,
        )
    ]

    return ToolResult(
        tool_name="get_service_health",
        success=True,
        data={"service": service, "info": info},
        evidence_items=evidence_items,
    )


# ---------------------------------------------------------------------------
# Tool Registry
# ---------------------------------------------------------------------------

class ToolRegistry:
    """Central registry validating tool dispatch and argument schemas."""

    def __init__(self):
        self._tools: Dict[str, ToolDefinition] = {}

    def register(self, tool: ToolDefinition) -> None:
        self._tools[tool.name] = tool

    def get(self, name: str) -> Optional[ToolDefinition]:
        return self._tools.get(name)

    def list_tools(self) -> List[Dict[str, Any]]:
        return [tool.to_schema() for tool in self._tools.values()]

    def execute(self, name: str, args: Dict[str, Any]) -> ToolResult:
        """Execute a registered tool with validated arguments.

        - Checks that all required arguments are present.
        - Rejects any arguments not declared in the tool's schema (prevents model‑controlled arbitrary parameters).
        - Validates basic type constraints.
        """
        tool = self.get(name)
        if tool is None:
            return ToolResult(
                tool_name=name,
                success=False,
                error=f"Tool '{name}' is not registered. Valid tools: {list(self._tools.keys())}",
            )

        # Validate required arguments against parameters_schema
        schema = tool.parameters_schema
        required_fields = schema.get("required", [])
        properties = schema.get("properties", {})

        missing = [field for field in required_fields if field not in args or args[field] is None]
        if missing:
            return ToolResult(
                tool_name=name,
                success=False,
                error=f"Invalid arguments for tool '{name}': missing required fields {missing}",
            )

        # Reject undeclared arguments (security measure)
        unexpected = [key for key in args if key not in properties]
        if unexpected:
            return ToolResult(
                tool_name=name,
                success=False,
                error=f"Invalid arguments for tool '{name}': unexpected fields {unexpected}",
            )

        # Validate types where specified
        for key, val in args.items():
            if key in properties:
                expected_type = properties[key].get("type")
                if expected_type == "string" and not isinstance(val, str):
                    return ToolResult(
                        tool_name=name,
                        success=False,
                        error=f"Invalid argument '{key}': expected string, got {type(val).__name__}",
                    )
                if expected_type == "number" and not isinstance(val, (int, float)):
                    return ToolResult(
                        tool_name=name,
                        success=False,
                        error=f"Invalid argument '{key}': expected number, got {type(val).__name__}",
                    )
                if expected_type == "integer" and not isinstance(val, int):
                    return ToolResult(
                        tool_name=name,
                        success=False,
                        error=f"Invalid argument '{key}': expected integer, got {type(val).__name__}",
                    )

        try:
            return tool.func(**args)
        except TypeError as exc:
            return ToolResult(
                tool_name=name,
                success=False,
                error=f"Argument mismatch for tool '{name}': {exc}",
            )
        except Exception as exc:
            return ToolResult(
                tool_name=name,
                success=False,
                error=f"Tool '{name}' failed with unhandled exception: {exc}",
            )


def build_default_tool_registry() -> ToolRegistry:
    """Build the standard ToolRegistry loaded with Demo Shop evidence tools."""
    registry = ToolRegistry()

    registry.register(
        ToolDefinition(
            name="query_log_patterns",
            description="Searches recorded demo-shop logs for new or spiking event patterns between pre-incident baseline and incident windows.",
            parameters_schema={
                "type": "object",
                "properties": {
                    "service": {"type": "string", "description": "Service name (e.g. payments, checkout)"},
                    "incident_start": {"type": "string", "description": "ISO-8601 UTC timestamp of incident start"},
                    "incident_end": {"type": "string", "description": "ISO-8601 UTC timestamp of incident end"},
                    "query_base_url": {"type": "string", "description": "Optional Grafana explore URL"},
                    "datasource_uid": {"type": "string", "description": "Optional Loki datasource UID"},
                },
                "required": ["service", "incident_start", "incident_end"],
            },
            func=query_log_patterns,
        )
    )

    registry.register(
        ToolDefinition(
            name="query_deploy_events",
            description="Queries recorded deployment history for a service to identify recent version or config changes.",
            parameters_schema={
                "type": "object",
                "properties": {
                    "service": {"type": "string", "description": "Service name"},
                    "start_time": {"type": "string", "description": "Optional start timestamp"},
                    "end_time": {"type": "string", "description": "Optional end timestamp"},
                },
                "required": ["service"],
            },
            func=query_deploy_events,
        )
    )

    registry.register(
        ToolDefinition(
            name="query_metric_anomalies",
            description="Queries recorded statistical metric anomalies (latency, error rate, queue depth, cert days) against baseline.",
            parameters_schema={
                "type": "object",
                "properties": {
                    "service": {"type": "string", "description": "Service name"},
                    "metric_name": {"type": "string", "description": "Optional specific metric name"},
                    "start_time": {"type": "string", "description": "Optional start timestamp"},
                    "end_time": {"type": "string", "description": "Optional end timestamp"},
                },
                "required": ["service"],
            },
            func=query_metric_anomalies,
        )
    )

    registry.register(
        ToolDefinition(
            name="get_service_health",
            description="Gets the recorded operational health status, dependencies, and dependents for a service.",
            parameters_schema={
                "type": "object",
                "properties": {
                    "service": {"type": "string", "description": "Service name"},
                },
                "required": ["service"],
            },
            func=get_service_health,
        )
    )

    return registry
