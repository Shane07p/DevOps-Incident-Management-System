"""
OpenTelemetry and structured logging configuration for Demo Shop v1.

Provides real OpenTelemetry metric instruments and JSON-formatted structured logging.
Operates in local mode without credentials or network dependencies, and connects
to Grafana Cloud / OTLP when valid endpoints are supplied (D34, #75).
"""

from __future__ import annotations

import datetime
import json
import logging
import sys
from typing import Any, Dict, Optional

from opentelemetry import metrics
from opentelemetry.metrics import CallbackOptions, Observation
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import (
    InMemoryMetricReader,
    PeriodicExportingMetricReader,
)
from opentelemetry.sdk.resources import Resource

from demo_shop.config import Config

def parse_otlp_headers(raw_h: str) -> dict:
    """Parse OTLP headers from a raw string.

    Supports three formats:
    1. "Basic <base64>" or "Bearer <token>" – returned as Authorization header.
    2. "k1=v1,k2=v2" – split into key/value pairs.
    3. Any other string – treated as raw Authorization header.
    """
    headers: dict = {}
    if raw_h.lower().startswith(("basic ", "bearer ")):
        headers["Authorization"] = raw_h
    elif "=" in raw_h:
        for part in raw_h.split(","):
            if "=" in part:
                k, v = part.split("=", 1)
                headers[k.strip()] = v.strip()
    else:
        headers["Authorization"] = raw_h
    return headers

logger = logging.getLogger("demo_shop")


class TelemetryManager:
    """Manages OpenTelemetry MeterProvider and metric instruments."""

    def __init__(self):
        self.meter_provider: Optional[MeterProvider] = None
        self.in_memory_reader: Optional[InMemoryMetricReader] = None
        self.meter = None

        # Gauges/dynamic state tracked for observable callbacks
        self._queue_depth_val: int = 0
        self._queue_lag_val: float = 0.0
        self._cert_days_val: int = 90
        self._fault_states: Dict[str, int] = {
            "slow_gateway": 0,
            "error_spike": 0,
            "bad_deploy": 0,
            "queue_backlog": 0,
            "expiring_certificate": 0,
        }

        self._setup_metrics()

    def _setup_metrics(self):
        resource = Resource.create(
            {
                "service.name": Config.SERVICE_NAME,
                "service.environment": Config.ENVIRONMENT,
                "service.version": Config.DEFAULT_VERSION,
            }
        )

        readers = []

        if Config.is_remote_telemetry_enabled():
            try:
                from opentelemetry.exporter.otlp.proto.http.metric_exporter import (
                    OTLPMetricExporter,
                )

                endpoint = Config.OTEL_EXPORTER_OTLP_ENDPOINT
                headers = {}
                if Config.OTEL_EXPORTER_OTLP_HEADERS:
                    headers = parse_otlp_headers(Config.OTEL_EXPORTER_OTLP_HEADERS)

                if (
                    endpoint.lower().startswith("http://")
                    and Config.ENVIRONMENT.lower() != "development"
                ):
                    raise ValueError(
                        "OTLP endpoint must use HTTPS outside local development"
                    )

                otlp_exporter = OTLPMetricExporter(
                    endpoint=f"{endpoint.rstrip('/')}/v1/metrics",
                    headers=headers,
                    timeout=5.0,
                )

                readers.append(
                    PeriodicExportingMetricReader(
                        otlp_exporter, export_interval_millis=5000
                    )
                )
                logger.info(
                    "OpenTelemetry remote OTLP metric exporter initialized for %s",
                    endpoint,
                )
            except Exception as exc:
                logger.warning(
                    "Failed to initialize remote OTLP metric exporter: %s. Operating in local mode.",
                    exc,
                )

        # Always maintain an InMemoryMetricReader for fast local test inspection
        self.in_memory_reader = InMemoryMetricReader()
        readers.append(self.in_memory_reader)

        self.meter_provider = MeterProvider(resource=resource, metric_readers=readers)
        metrics.set_meter_provider(self.meter_provider)
        self.meter = self.meter_provider.get_meter("demo_shop")

        # Define metric instruments
        self.http_requests_total = self.meter.create_counter(
            name="demo_http_requests_total",
            description="Total HTTP requests processed by demo shop",
            unit="1",
        )

        self.http_request_duration_seconds = self.meter.create_histogram(
            name="demo_http_request_duration_seconds",
            description="HTTP request processing latency in seconds",
            unit="s",
        )

        self.deploy_events_total = self.meter.create_counter(
            name="demo_deploy_events_total",
            description="Total software deployment events emitted",
            unit="1",
        )

        self.ssl_errors_total = self.meter.create_counter(
            name="demo_ssl_errors_total",
            description="Total simulated SSL/TLS certificate errors",
            unit="1",
        )

        # Observable gauges for real-time state reporting
        self.meter.create_observable_gauge(
            name="demo_queue_depth",
            callbacks=[self._observe_queue_depth],
            description="Current depth of order processing queue",
            unit="1",
        )

        self.meter.create_observable_gauge(
            name="demo_queue_lag_seconds",
            callbacks=[self._observe_queue_lag],
            description="Processing lag of oldest order in queue in seconds",
            unit="s",
        )

        self.meter.create_observable_gauge(
            name="demo_certificate_days_until_expiry",
            callbacks=[self._observe_cert_days],
            description="Days remaining before payment gateway TLS certificate expires",
            unit="d",
        )

        self.meter.create_observable_gauge(
            name="demo_fault_active",
            callbacks=[self._observe_fault_active],
            description="Active state of fault switches (1 if enabled, 0 if disabled)",
            unit="1",
        )

    def _observe_queue_depth(self, options: CallbackOptions):
        yield Observation(self._queue_depth_val, {"queue": "order-fulfillment"})

    def _observe_queue_lag(self, options: CallbackOptions):
        yield Observation(self._queue_lag_val, {"queue": "order-fulfillment"})

    def _observe_cert_days(self, options: CallbackOptions):
        yield Observation(self._cert_days_val, {"host": "gateway.payments.internal"})

    def _observe_fault_active(self, options: CallbackOptions):
        for fault_name, active in self._fault_states.items():
            yield Observation(active, {"fault": fault_name})

    def update_queue_metrics(self, depth: int, lag_seconds: float):
        self._queue_depth_val = max(0, depth)
        self._queue_lag_val = max(0.0, lag_seconds)

    def set_cert_days(self, days: int):
        self._cert_days_val = days

    def set_fault_active(self, fault_name: str, active: bool):
        self._fault_states[fault_name] = 1 if active else 0

    def shutdown(self):
        if self.meter_provider:
            self.meter_provider.shutdown()


# Global telemetry singleton
telemetry = TelemetryManager()


# ---------------------------------------------------------------------------
# Structured Logging
# ---------------------------------------------------------------------------

class StructuredLogger:
    """Emits machine-readable JSON structured logs to stdout."""

    @staticmethod
    def _sanitize(data: Any) -> Any:
        if isinstance(data, dict):
            sanitized = {}
            for k, v in data.items():
                if any(secret in k.lower() for secret in ("token", "password", "secret", "card_number")):
                    sanitized[k] = "[REDACTED]"
                else:
                    sanitized[k] = StructuredLogger._sanitize(v)
            return sanitized
        if isinstance(data, list):
            return [StructuredLogger._sanitize(item) for item in data]
        return data

    @classmethod
    def emit(
        cls,
        level: str,
        service: str,
        event: str,
        request_id: Optional[str] = None,
        fault_id: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
    ):
        record = {
            "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "level": level.upper(),
            "service": service,
            "event": event,
            "environment": Config.ENVIRONMENT,
        }
        if request_id:
            record["request_id"] = request_id
        if fault_id:
            record["fault_id"] = fault_id
        if details:
            record["details"] = cls._sanitize(details)

        json_line = json.dumps(record, separators=(",", ":"))
        sys.stdout.write(json_line + "\n")
        sys.stdout.flush()

    @classmethod
    def info(cls, service: str, event: str, **kwargs):
        cls.emit("INFO", service, event, **kwargs)

    @classmethod
    def warning(cls, service: str, event: str, **kwargs):
        cls.emit("WARN", service, event, **kwargs)

    @classmethod
    def error(cls, service: str, event: str, **kwargs):
        cls.emit("ERROR", service, event, **kwargs)
