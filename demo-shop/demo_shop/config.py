"""Configuration for Demo Shop v1."""

from __future__ import annotations
import os


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

    ENVIRONMENT: str = os.getenv("ENVIRONMENT", "development")
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO").upper()

    @classmethod
    def is_remote_telemetry_enabled(cls) -> bool:
        """Returns True if remote OTLP export endpoint is configured."""
        return bool(cls.OTEL_EXPORTER_OTLP_ENDPOINT)
