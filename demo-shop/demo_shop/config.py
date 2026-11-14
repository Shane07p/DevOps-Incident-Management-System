"""Configuration for Demo Shop v1."""

from __future__ import annotations
import os

DEFAULT_METRIC_EXPORT_INTERVAL_MS = 60_000


def _metric_export_interval_ms() -> int:
    """How often metrics are pushed, from OTEL_METRIC_EXPORT_INTERVAL (milliseconds, as in the OTel spec).

    60 s by default: every push is outbound traffic on the host's free allowance, and Grafana Cloud's
    free tier counts one data point per series per minute. Anything unreadable falls back to the
    default, and nothing goes below 1 s.
    """
    raw = os.getenv("OTEL_METRIC_EXPORT_INTERVAL", "").strip()
    try:
        value = int(raw) if raw else DEFAULT_METRIC_EXPORT_INTERVAL_MS
    except ValueError:
        return DEFAULT_METRIC_EXPORT_INTERVAL_MS
    return max(value, 1_000)


class Config:
    PORT: int = int(os.getenv("PORT", os.getenv("DEMO_SHOP_PORT", "8001")))
    HOST: str = os.getenv("HOST", "0.0.0.0")
    SERVICE_NAME: str = os.getenv("SERVICE_NAME", "demo-shop")
    DEFAULT_VERSION: str = "v1.0.0"
    BAD_DEPLOY_VERSION: str = "v1.0.1-broken"

    # OpenTelemetry / Grafana Cloud settings (D34, #75)
    OTEL_EXPORTER_OTLP_ENDPOINT: str = os.getenv(
        "OTEL_EXPORTER_OTLP_ENDPOINT",
        os.getenv("GRAFANA_OTLP_ENDPOINT", ""),
    ).strip()

    OTEL_EXPORTER_OTLP_HEADERS: str = os.getenv(
        "OTEL_EXPORTER_OTLP_HEADERS",
        os.getenv("GRAFANA_OTLP_TOKEN", ""),
    ).strip()

    OTEL_METRIC_EXPORT_INTERVAL_MS: int = _metric_export_interval_ms()

    ENVIRONMENT: str = os.getenv("ENVIRONMENT", "development")
    CONTROL_TOKEN: str = os.getenv("DEMO_SHOP_CONTROL_TOKEN", "").strip()
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO").upper()

    # Incident backend deployment webhook settings (FR-20.1, AC-5)
    BACKEND_URL: str = os.getenv("BACKEND_URL", os.getenv("INCIDENT_BACKEND_URL", "http://localhost:8080")).rstrip("/")
    TOOL_TOKEN: str = os.getenv("TOOL_TOKEN", os.getenv("BACKEND_TOOL_TOKEN", "")).strip()
    DEPLOY_SERVICE_NAME: str = os.getenv("DEPLOY_SERVICE_NAME", "checkout").strip()

    ALLOWED_TRAFFIC_ORIGINS: tuple[str, ...] = tuple(
        origin.strip().rstrip("/")
        for origin in os.getenv(
            "ALLOWED_TRAFFIC_ORIGINS",
            f"http://localhost:{PORT},http://127.0.0.1:{PORT},http://testserver,http://demo-shop:{PORT}",
        ).split(",")
        if origin.strip()
    )


    @classmethod
    def is_remote_telemetry_enabled(cls) -> bool:
        """Returns True if remote OTLP export endpoint is configured."""
        return bool(cls.OTEL_EXPORTER_OTLP_ENDPOINT)
