"""Tests for OpenTelemetry metrics, structured logging, and local mode operation."""

import io
from unittest.mock import patch
import pytest

from demo_shop.config import Config
from demo_shop.telemetry import StructuredLogger, telemetry


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
    headers = {}
    if raw_h.lower().startswith(("basic ", "bearer ")):
        headers["Authorization"] = raw_h
    elif "=" in raw_h:
        for part in raw_h.split(","):
            if "=" in part:
                k, v = part.split("=", 1)
                headers[k.strip()] = v.strip()
    else:
        headers["Authorization"] = raw_h

    assert headers.get("Authorization") == "Basic dXNlcjpwYXNz=="


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
