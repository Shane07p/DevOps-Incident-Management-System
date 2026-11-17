"""Tests for OpenTelemetry metrics, structured logging, and local mode operation."""

import io
from unittest.mock import patch
import pytest

from demo_shop.config import Config
from demo_shop.telemetry import StructuredLogger, telemetry, parse_otlp_headers


def test_local_telemetry_mode_no_credentials():
    # In local mode, remote telemetry is disabled and no crash occurs
    assert Config.is_remote_telemetry_enabled() is False
    assert telemetry.in_memory_reader is not None


def test_metrics_collection_on_http_requests(client):
    # Perform a few requests
    client.get("/health")
    client.get("/products")

    # Read collected metrics from the OpenTelemetry InMemoryMetricReader
    data = telemetry.in_memory_reader.get_metrics_data()
    assert data is not None

    metric_names = [m.name for rm in data.resource_metrics for sm in rm.scope_metrics for m in sm.metrics]
    assert "demo_http_requests_total" in metric_names
    assert "demo_http_request_duration_seconds" in metric_names


def test_structured_logger_emits_valid_json():
    captured = io.StringIO()
    with patch("sys.stdout", captured):
        StructuredLogger.info(
            service="checkout",
            event="order.created",
            request_id="req-12345",
            details={"order_id": "ord-999", "amount": 150.0},
        )

    output = captured.getvalue().strip()
    assert output.startswith("{") and output.endswith("}")

    import json
    parsed = json.loads(output)
    assert parsed["level"] == "INFO"
    assert parsed["service"] == "checkout"
    assert parsed["event"] == "order.created"
    assert parsed["request_id"] == "req-12345"
    assert parsed["details"]["order_id"] == "ord-999"
    assert "timestamp" in parsed


def test_structured_logger_redacts_sensitive_fields():
    data = {
        "user": "test_user",
        "card_token": "tok_visa_secret_12345",
        "nested": {"password": "SuperSecretPassword123!"},
    }
    sanitized = StructuredLogger._sanitize(data)
    assert sanitized["user"] == "test_user"
    assert sanitized["card_token"] == "[REDACTED]"
    assert sanitized["nested"]["password"] == "[REDACTED]"


def test_otlp_auth_header_parsing_with_base64_padding(monkeypatch):
    from demo_shop.telemetry import TelemetryManager
    # Header containing Base64 padding '='
    base64_auth = "Basic dXNlcjpwYXNz=="
    monkeypatch.setattr(Config, "OTEL_EXPORTER_OTLP_ENDPOINT", "http://fake-collector:4318")
    monkeypatch.setattr(Config, "OTEL_EXPORTER_OTLP_HEADERS", base64_auth)

    raw_h = Config.OTEL_EXPORTER_OTLP_HEADERS
    headers = parse_otlp_headers(raw_h)
    assert headers.get("Authorization") == "Basic dXNlcjpwYXNz=="


def test_otlp_headers_as_grafana_cloud_generates_them_are_percent_decoded():
    # Grafana Cloud's OpenTelemetry page gives "Authorization=Basic%20<base64>"; sent as is, it is a 401.
    assert parse_otlp_headers("Authorization=Basic%20dXNlcjpwYXNz") == {"Authorization": "Basic dXNlcjpwYXNz"}
    assert parse_otlp_headers("Basic%20dXNlcjpwYXNz==") == {"Authorization": "Basic dXNlcjpwYXNz=="}
    assert parse_otlp_headers(" Authorization=Basic dXNlcjpwYXNz ") == {"Authorization": "Basic dXNlcjpwYXNz"}


def test_metric_export_interval_defaults_to_a_minute_and_reads_the_otel_variable(monkeypatch):
    from demo_shop.config import _metric_export_interval_ms

    monkeypatch.delenv("OTEL_METRIC_EXPORT_INTERVAL", raising=False)
    assert _metric_export_interval_ms() == 60_000
    monkeypatch.setenv("OTEL_METRIC_EXPORT_INTERVAL", "30000")
    assert _metric_export_interval_ms() == 30_000
    monkeypatch.setenv("OTEL_METRIC_EXPORT_INTERVAL", "soon")
    assert _metric_export_interval_ms() == 60_000
    monkeypatch.setenv("OTEL_METRIC_EXPORT_INTERVAL", "10")
    assert _metric_export_interval_ms() == 1_000


def test_remote_export_is_gzipped_and_pushed_at_the_configured_interval(monkeypatch):
    from opentelemetry.exporter.otlp.proto.http import Compression
    from demo_shop import telemetry as telemetry_module

    monkeypatch.setattr(Config, "OTEL_EXPORTER_OTLP_ENDPOINT", "https://otlp.example.test/otlp")
    monkeypatch.setattr(Config, "OTEL_EXPORTER_OTLP_HEADERS", "Authorization=Basic%20dXNlcjpwYXNz")
    monkeypatch.setattr(Config, "OTEL_METRIC_EXPORT_INTERVAL_MS", 60_000)
    with patch("opentelemetry.exporter.otlp.proto.http.metric_exporter.OTLPMetricExporter") as exporter,             patch.object(telemetry_module, "PeriodicExportingMetricReader") as reader:
        telemetry_module.TelemetryManager()

    exporter.assert_called_once()
    sent = exporter.call_args.kwargs
    assert sent["endpoint"] == "https://otlp.example.test/otlp/v1/metrics"
    assert sent["headers"] == {"Authorization": "Basic dXNlcjpwYXNz"}
    assert sent["compression"] == Compression.Gzip
    assert reader.call_args.kwargs["export_interval_millis"] == 60_000


def test_metric_labels_use_route_template_not_raw_path(client):
    # Request a product with an ID parameter
    resp = client.get("/products/prod-101")
    assert resp.status_code == 200

    data = telemetry.in_memory_reader.get_metrics_data()
    endpoints = []
    for rm in data.resource_metrics:
        for sm in rm.scope_metrics:
            for m in sm.metrics:
                if m.name == "demo_http_requests_total":
                    for dp in m.data.data_points:
                        endpoints.append(dp.attributes.get("endpoint"))

    # Must contain the route template '/products/{product_id}' and NOT raw '/products/prod-101'
    assert "/products/{product_id}" in endpoints
    assert "/products/prod-101" not in endpoints


def test_checkout_logs_hash_customer_email(client):
    import json
    captured = io.StringIO()
    payload = {
        "customer_email": "sensitive_user@example.com",
        "items": [{"product_id": "prod-101", "quantity": 1, "unit_price": 10.0}],
        "payment_method": "credit_card",
    }
    with patch("sys.stdout", captured):
        resp = client.post("/checkout", json=payload)
        assert resp.status_code == 200

    log_output = captured.getvalue()
    assert "sensitive_user@example.com" not in log_output
    assert "customer_hash" in log_output


def test_metrics_carry_the_service_each_route_belongs_to(client):
    # Settle reads a service's metrics by this label, so it must name the service, not demo-shop.
    client.post("/payments/authorize", json={"order_id": "ord-label-1", "amount": 10.0, "currency": "INR"})
    client.get("/search", params={"q": "shoe"})
    client.get("/health")

    data = telemetry.in_memory_reader.get_metrics_data()
    services = {}
    for rm in data.resource_metrics:
        for sm in rm.scope_metrics:
            for m in sm.metrics:
                if m.name == "demo_http_requests_total":
                    for dp in m.data.data_points:
                        services[dp.attributes.get("endpoint")] = dp.attributes.get("service")

    assert services["/payments/authorize"] == "payments"
    assert services["/search"] == "search"
    assert services["/health"] == "demo-shop"
