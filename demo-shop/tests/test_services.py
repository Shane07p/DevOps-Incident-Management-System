"""Tests for core retail services: search, payments, and checkout."""

import pytest


def test_health_endpoint(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["service"] == "demo-shop"
    assert "version" in data


def test_ready_endpoint(client):
    resp = client.get("/ready")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "queue_depth" in data


def test_list_products(client):
    resp = client.get("/products")
    assert resp.status_code == 200
    items = resp.json()
    assert isinstance(items, list)
    assert len(items) == 10
    first = items[0]
    assert "id" in first
    assert "name" in first
    assert "price" in first


def test_get_product_by_id(client):
    resp = client.get("/products/prod-101")
    assert resp.status_code == 200
    data = resp.json()
    assert data["id"] == "prod-101"
    assert "Headphones" in data["name"]


def test_get_unknown_product_returns_404(client):
    resp = client.get("/products/nonexistent-id")
    assert resp.status_code == 404


def test_search_products(client):
    resp = client.get("/search?q=headphones")
    assert resp.status_code == 200
    data = resp.json()
    assert data["count"] >= 1
    assert any("Headphones" in p["name"] for p in data["results"])


def test_search_products_empty_query_returns_all(client):
    resp = client.get("/search")
    assert resp.status_code == 200
    data = resp.json()
    assert data["count"] == 10


def test_search_products_no_match(client):
    resp = client.get("/search?q=unicorn-keyboard-with-wings")
    assert resp.status_code == 200
    data = resp.json()
    assert data["count"] == 0
    assert data["results"] == []


def test_payments_authorize_success(client):
    payload = {
        "order_id": "ord-test-1",
        "amount": 99.50,
        "currency": "USD",
        "card_token": "tok_visa_4242",
    }
    resp = client.post("/payments/authorize", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "authorized"
    assert data["order_id"] == "ord-test-1"
    assert data["amount"] == 99.50
    assert "transaction_id" in data
    assert data["latency_ms"] >= 0


def test_payments_validation_error(client):
    # Invalid amount <= 0
    payload = {
        "order_id": "ord-test-1",
        "amount": -10.0,
        "currency": "USD",
        "card_token": "tok_visa_4242",
    }
    resp = client.post("/payments/authorize", json=payload)
    assert resp.status_code == 422


def test_checkout_flow_success(client):
    payload = {
        "customer_email": "shopper@example.com",
        "items": [
            {"product_id": "prod-101", "quantity": 1, "unit_price": 199.99},
            {"product_id": "prod-105", "quantity": 2, "unit_price": 39.99},
        ],
        "payment_method": "credit_card",
    }
    resp = client.post("/checkout", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "completed"
    assert data["order_id"].startswith("ord-")
    assert data["total_amount"] == 279.97  # 199.99 + (39.99 * 2)
    assert data["payment"]["status"] == "authorized"
    assert data["version"] == "v1.0.0"


def test_checkout_validation_error_empty_items(client):
    payload = {
        "customer_email": "shopper@example.com",
        "items": [],
        "payment_method": "credit_card",
    }
    resp = client.post("/checkout", json=payload)
    assert resp.status_code == 422
