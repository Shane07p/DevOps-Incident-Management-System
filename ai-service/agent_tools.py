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
from metric_baseline import (
    DEFAULT_RECORDED_METRICS,
    find_metric_anomalies,
)
from deploy_window import (
    DEFAULT_RECORDED_DEPLOYS,
    find_deploy_events,
)

DATA_DIR = Path(__file__).resolve().parent / "data" / "fixtures"
SCENARIOS_DIR = DATA_DIR / "scenarios"
DEFAULT_LOG_FIXTURE = SCENARIOS_DIR / "SCN-01" / "logs.jsonl"
if not DEFAULT_LOG_FIXTURE.exists():
    _legacy = DATA_DIR / "demo_shop_slow_gateway.jsonl"
    if _legacy.exists():
        DEFAULT_LOG_FIXTURE = _legacy


# ---------------------------------------------------------------------------
# Data Models
# ---------------------------------------------------------------------------

class EvidenceBundle(BaseModel):
    """Live evidence bundle gathered by the backend for an incident investigation (#242)."""
    model_config = ConfigDict(populate_by_name=True, alias_generator=alias_generators.to_camel)

    incident: Optional[Dict[str, Any]] = None
    timeline: List[Dict[str, Any]] = Field(default_factory=list)
    deploys: List[Dict[str, Any]] = Field(default_factory=list)
    logs: List[Dict[str, Any]] = Field(default_factory=list)
    metrics: List[Dict[str, Any]] = Field(default_factory=list)


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
DEMO_SHOP_RECORDED_DEPLOYS = DEFAULT_RECORDED_DEPLOYS


def query_deploy_events(
    service: str,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
    incident_start: Optional[str] = None,
    incident_end: Optional[str] = None,
    lookback_minutes: int = 120,
    include_dependencies: Optional[bool] = None,
) -> ToolResult:
    """
    Query recorded deployments for the target service within an optional time range.
    Defaults to including upstream dependencies when incident_start is provided.
    """
    if include_dependencies is None:
        include_dependencies = bool(incident_start)

    try:
        matching_deploys = find_deploy_events(
            deploys=DEMO_SHOP_RECORDED_DEPLOYS,
            service=service,
            incident_start=incident_start,
            incident_end=incident_end,
            start_time=start_time,
            end_time=end_time,
            lookback_minutes=lookback_minutes,
            include_dependencies=include_dependencies,
        )
    except Exception as exc:
        return ToolResult(
            tool_name="query_deploy_events",
            success=False,
            error=f"Invalid timestamp format: {exc}",
        )

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
DEMO_SHOP_RECORDED_METRICS = DEFAULT_RECORDED_METRICS


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
        metrics = find_metric_anomalies(
            service=service,
            metric_records=DEMO_SHOP_RECORDED_METRICS,
            metric_name=metric_name,
            start_time=start_time,
            end_time=end_time,
        )
    except Exception as exc:
        return ToolResult(
            tool_name="query_metric_anomalies",
            success=False,
            error=f"Invalid timestamp format: {exc}",
        )

    evidence_items: List[EvidenceItem] = []
    for idx, m in enumerate(metrics):
        ev_id = f"ev-metric-{idx}"
        content = f"Metric anomaly on '{service}': {m['metric']} - {m['observation']}"
        if m.get("query_url"):
            content += f" (Explore: {m['query_url']})"
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
                if expected_type == "boolean" and not isinstance(val, bool):
                    return ToolResult(
                        tool_name=name,
                        success=False,
                        error=f"Invalid argument '{key}': expected boolean, got {type(val).__name__}",
                    )
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
                if expected_type == "integer" and (not isinstance(val, int) or isinstance(val, bool)):
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
                    "incident_start": {"type": "string", "description": "ISO-8601 UTC timestamp of incident start"},
                    "incident_end": {"type": "string", "description": "Optional ISO-8601 UTC timestamp of incident end"},
                    "lookback_minutes": {"type": "integer", "description": "Lookback window in minutes (default: 120)"},
                    "include_dependencies": {"type": "boolean", "description": "Whether to include deployments of upstream dependencies (default: True when incident_start is provided)"},
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


def build_bundle_tool_registry(bundle: EvidenceBundle) -> ToolRegistry:
    """Build a ToolRegistry whose tools read directly from a live backend EvidenceBundle (#242)."""
    registry = ToolRegistry()

    def bundle_query_log_patterns(
        service: str,
        incident_start: str = "",
        incident_end: str = "",
        query_base_url: str = "",
        datasource_uid: str = "",
    ) -> ToolResult:
        counts: Dict[str, int] = {}
        sample_levels: Dict[str, str] = {}
        for entry in bundle.logs:
            msg = str(entry.get("message") or "").strip()
            if not msg:
                continue
            level = str(entry.get("level") or "INFO").upper()
            counts[msg] = counts.get(msg, 0) + 1
            sample_levels.setdefault(msg, level)

        sorted_msgs = sorted(
            counts.items(),
            key=lambda kv: (0 if sample_levels.get(kv[0]) in ("ERROR", "WARN") else 1, -kv[1], kv[0]),
        )
        evidence_items: List[EvidenceItem] = []
        patterns: List[Dict[str, Any]] = []
        for idx, (msg, count) in enumerate(sorted_msgs[:15]):
            level = sample_levels.get(msg, "INFO")
            item_dict = {"pattern": msg, "level": level, "count": count}
            patterns.append(item_dict)
            evidence_items.append(
                EvidenceItem(
                    id=f"ev-log-{idx}",
                    source_tool="query_log_patterns",
                    evidence_type="log_pattern",
                    content=f"[{level}] Log message '{msg}' on '{service}' occurred {count} time(s).",
                    details=item_dict,
                )
            )
        return ToolResult(
            tool_name="query_log_patterns",
            success=True,
            data={"service": service, "patterns_found": len(patterns), "patterns": patterns},
            evidence_items=evidence_items,
        )

    def bundle_query_deploy_events(
        service: str,
        start_time: Optional[str] = None,
        end_time: Optional[str] = None,
        incident_start: Optional[str] = None,
        incident_end: Optional[str] = None,
        lookback_minutes: int = 120,
        include_dependencies: Optional[bool] = None,
    ) -> ToolResult:
        matching: List[Dict[str, Any]] = []
        for dep in bundle.deploys:
            dep_service = str(dep.get("service") or "")
            if service and dep_service and dep_service.lower() != service.lower():
                # Keep deploys matching normalized hyphen/space name too
                if dep_service.lower().replace(" ", "-") != service.lower().replace(" ", "-"):
                    continue
            matching.append(dep)

        evidence_items: List[EvidenceItem] = []
        for idx, dep in enumerate(matching):
            commit = dep.get("commit", "unknown")
            version = dep.get("version", "unknown")
            deployed_at = dep.get("deployedAt") or dep.get("deployed_at") or ""
            author = dep.get("author", "unknown")
            svc = dep.get("service", service)
            evidence_items.append(
                EvidenceItem(
                    id=f"ev-deploy-{idx}",
                    source_tool="query_deploy_events",
                    evidence_type="deploy_event",
                    content=f"Deploy commit {commit} to '{svc}' deployed version {version} at {deployed_at} by {author}.",
                    details=dep,
                )
            )
        return ToolResult(
            tool_name="query_deploy_events",
            success=True,
            data={"service": service, "deploys": matching},
            evidence_items=evidence_items,
        )

    def bundle_query_metric_anomalies(
        service: str,
        metric_name: Optional[str] = None,
        start_time: Optional[str] = None,
        end_time: Optional[str] = None,
    ) -> ToolResult:
        evidence_items: List[EvidenceItem] = []
        anomalies: List[Dict[str, Any]] = []
        for idx, series in enumerate(bundle.metrics):
            m_name = str(series.get("metric") or "")
            if metric_name and m_name != metric_name:
                continue
            points = series.get("points") or []
            values = [float(p["value"]) for p in points if isinstance(p, dict) and p.get("value") is not None]
            if values:
                min_v, max_v, latest_v = min(values), max(values), values[-1]
                avg_v = sum(values) / len(values)
                summary = f"Metric '{m_name}' on '{service}': min={min_v:.4f}, avg={avg_v:.4f}, max={max_v:.4f}, latest={latest_v:.4f} ({len(values)} points)."
            else:
                summary = f"Metric '{m_name}' on '{service}': no data points in window."
            record = {"metric": m_name, "point_count": len(values), "observation": summary}
            anomalies.append(record)
            evidence_items.append(
                EvidenceItem(
                    id=f"ev-metric-{idx}",
                    source_tool="query_metric_anomalies",
                    evidence_type="metric_anomaly",
                    content=summary,
                    details=record,
                )
            )
        return ToolResult(
            tool_name="query_metric_anomalies",
            success=True,
            data={"service": service, "anomalies": anomalies},
            evidence_items=evidence_items,
        )

    def bundle_get_service_health(service: str) -> ToolResult:
        inc = bundle.incident or {}
        title = inc.get("title", "")
        status_val = inc.get("status", "OPEN")
        severity = inc.get("severity", "")
        content = f"Incident '{inc.get('reference', '')}' on '{service}' ({severity}, status={status_val}): {title}"
        evidence_items = [
            EvidenceItem(
                id="ev-health-0",
                source_tool="get_service_health",
                evidence_type="service_health",
                content=content,
                details=inc,
            )
        ]
        return ToolResult(
            tool_name="get_service_health",
            success=True,
            data={"service": service, "incident": inc},
            evidence_items=evidence_items,
        )

    def bundle_query_timeline_entries() -> ToolResult:
        evidence_items: List[EvidenceItem] = []
        for idx, entry in enumerate(bundle.timeline):
            ev_type = entry.get("eventType") or entry.get("event_type") or "EVENT"
            actor = entry.get("actor") or "system"
            details = entry.get("details") or ""
            recorded = entry.get("recordedAt") or entry.get("recorded_at") or ""
            evidence_items.append(
                EvidenceItem(
                    id=f"ev-timeline-{idx}",
                    source_tool="query_timeline_entries",
                    evidence_type="timeline_entry",
                    content=f"[{recorded}] {ev_type} by {actor}: {details}",
                    details=entry,
                )
            )
        return ToolResult(
            tool_name="query_timeline_entries",
            success=True,
            data={"entries": bundle.timeline},
            evidence_items=evidence_items,
        )

    registry.register(
        ToolDefinition(
            name="query_log_patterns",
            description="Searches incident log bundle for error/warning and frequent log patterns.",
            parameters_schema={
                "type": "object",
                "properties": {
                    "service": {"type": "string", "description": "Service name"},
                    "incident_start": {"type": "string", "description": "Optional ISO-8601 UTC start"},
                    "incident_end": {"type": "string", "description": "Optional ISO-8601 UTC end"},
                    "query_base_url": {"type": "string"},
                    "datasource_uid": {"type": "string"},
                },
                "required": ["service"],
            },
            func=bundle_query_log_patterns,
        )
    )
    registry.register(
        ToolDefinition(
            name="query_deploy_events",
            description="Queries recent deployments for the incident's service from the evidence bundle.",
            parameters_schema={
                "type": "object",
                "properties": {
                    "service": {"type": "string", "description": "Service name"},
                    "start_time": {"type": "string"},
                    "end_time": {"type": "string"},
                    "incident_start": {"type": "string"},
                    "incident_end": {"type": "string"},
                    "lookback_minutes": {"type": "integer"},
                    "include_dependencies": {"type": "boolean"},
                },
                "required": ["service"],
            },
            func=bundle_query_deploy_events,
        )
    )
    registry.register(
        ToolDefinition(
            name="query_metric_anomalies",
            description="Queries metric series from the incident's evidence bundle.",
            parameters_schema={
                "type": "object",
                "properties": {
                    "service": {"type": "string", "description": "Service name"},
                    "metric_name": {"type": "string"},
                    "start_time": {"type": "string"},
                    "end_time": {"type": "string"},
                },
                "required": ["service"],
            },
            func=bundle_query_metric_anomalies,
        )
    )
    registry.register(
        ToolDefinition(
            name="get_service_health",
            description="Gets incident context and service status from the evidence bundle.",
            parameters_schema={
                "type": "object",
                "properties": {
                    "service": {"type": "string", "description": "Service name"},
                },
                "required": ["service"],
            },
            func=bundle_get_service_health,
        )
    )
    registry.register(
        ToolDefinition(
            name="query_timeline_entries",
            description="Reads the incident timeline entries from the evidence bundle.",
            parameters_schema={
                "type": "object",
                "properties": {},
            },
            func=bundle_query_timeline_entries,
        )
    )
    return registry
