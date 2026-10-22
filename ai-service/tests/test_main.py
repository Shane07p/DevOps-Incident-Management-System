"""
Tests for ai-service.

Validates:
  - GET  /health returns 200 instant liveness check
  - GET  /ready checks Ollama reachability and model pulled status
  - POST /suggest returns 200 with the exact AI contract shape:
      - aiGenerated == True (FR-18.2)
      - suggestions contains step and basedOn
      - basedOn uses IDs strictly from pastIncidents
  - 422 validation on empty or exceeding min/max length for title and description
  - 422 on invalid/missing request payload
  - POST /embed returns 200 with embedding vector and model
      - kind support ("document" -> "search_document: ", "query" -> "search_query: ")
      - rejection of booleans, NaN, Inf, empty vectors
      - handles timeout, connection errors, bad URLs with 503
      - handles 500/malformed Ollama responses with 502
"""

from unittest.mock import AsyncMock, MagicMock, patch
import httpx
import pytest
from fastapi.testclient import TestClient

from main import app, get_ollama_timeout


@pytest.fixture(autouse=True)
def isolate_environment(monkeypatch):
    """Ensure tests run with predictable environment variables and no live Ollama calls."""
    monkeypatch.setenv("OLLAMA_URL", "http://localhost:11434")
    monkeypatch.setenv("OLLAMA_EMBED_MODEL", "nomic-embed-text")
    monkeypatch.setenv("OLLAMA_TIMEOUT", "10")


client = TestClient(app)


# ---------------------------------------------------------------------------
# Health endpoint tests (Instant liveness)
# ---------------------------------------------------------------------------

def test_health_returns_200():
    response = client.get("/health")
    assert response.status_code == 200


def test_health_payload():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["service"] == "ai-service"
    assert data["version"] == "0.2.0"
    # /health is instant and independent of Ollama
    assert "ollama" not in data


# ---------------------------------------------------------------------------
# Ready endpoint tests (Ollama reachability + model pulled)
# ---------------------------------------------------------------------------

def test_ready_reports_ok_when_ollama_up_and_model_pulled():
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "models": [{"name": "nomic-embed-text:latest"}]
    }
    mock_client.get.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.get("/ready")

    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["ollama"]["reachable"] is True
    assert data["ollama"]["model_pulled"] is True
    assert data["ollama"]["model"] == "nomic-embed-text"


def test_ready_reports_not_ready_when_model_not_pulled():
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "models": [{"name": "llama3.2:1b"}]
    }
    mock_client.get.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.get("/ready")

    assert response.status_code == 503
    data = response.json()
    assert data["status"] == "not_ready"
    assert data["ollama"]["reachable"] is True
    assert data["ollama"]["model_pulled"] is False


def test_ready_reports_not_ready_when_ollama_unavailable():
    mock_client = AsyncMock()
    mock_client.get.side_effect = httpx.ConnectError("Connection refused")

    with patch("main._get_client", return_value=mock_client):
        response = client.get("/ready")

    assert response.status_code == 503
    data = response.json()
    assert data["status"] == "not_ready"
    assert data["ollama"]["reachable"] is False
    assert data["ollama"]["model_pulled"] is False


# ---------------------------------------------------------------------------
# Suggest endpoint tests (Contract shape compliance)
# ---------------------------------------------------------------------------

def test_suggest_returns_200_and_exact_contract_shape():
    payload = {
        "incident": {
            "title": "Payment gateway timeout",
            "description": "504 Gateway Timeout on checkout API under peak traffic",
            "service": "Payments",
            "severity": "SEV2",
        },
        "pastIncidents": [
            {
                "id": 12,
                "title": "Database connection pool timeout",
                "description": "Connection pool exhausted on checkout workers",
                "fix": "Increased HikariCP max pool size",
            }
        ],
    }
    response = client.post("/suggest", json=payload)
    assert response.status_code == 200
    data = response.json()

    assert data["aiGenerated"] is True
    assert isinstance(data["suggestions"], list)
    assert len(data["suggestions"]) > 0

    first_suggestion = data["suggestions"][0]
    assert first_suggestion["step"] == "Check the payment gateway timeout"
    assert first_suggestion["basedOn"] == [12]

    assert "status" not in first_suggestion
    assert "id" not in first_suggestion
    assert "similar_incidents" not in data
    assert "confidence" not in data


def test_suggest_based_on_uses_provided_past_incident_ids():
    payload = {
        "incident": {
            "title": "Orders failing with 500 internal server error",
            "description": "Downstream inventory service unreachable",
            "service": "Orders",
            "severity": "SEV1",
        },
        "pastIncidents": [
            {
                "id": 88,
                "title": "Inventory network partition",
                "description": "Circuit breaker did not trip",
                "fix": "Configured fallback circuit breaker",
            },
            {
                "id": 99,
                "title": "Deadlock in orders database",
                "description": "Concurrent lock contention on inventory table",
                "fix": "Reordered table updates in transaction",
            },
        ],
    }
    response = client.post("/suggest", json=payload)
    assert response.status_code == 200
    data = response.json()

    assert data["suggestions"][0]["basedOn"] == [88]
    assert data["suggestions"][1]["basedOn"] == [88, 99]

    all_based_on = [
        item for s in data["suggestions"] for item in s["basedOn"]
    ]
    assert "INC-12" not in all_based_on
    assert "INC-44" not in all_based_on


def test_suggest_with_empty_past_incidents():
    payload = {
        "incident": {
            "title": "New unknown anomaly",
            "description": "Service experiencing intermittent CPU spikes without known precedent",
            "service": "Analytics",
            "severity": "SEV3",
        },
        "pastIncidents": [],
    }
    response = client.post("/suggest", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["aiGenerated"] is True
    assert data["suggestions"] == []


# ---------------------------------------------------------------------------
# Suggest Validation tests (422 Unprocessable Entity)
# ---------------------------------------------------------------------------

def test_suggest_validation_error_on_missing_body():
    response = client.post("/suggest", json={})
    assert response.status_code == 422


def test_suggest_validation_error_on_empty_title():
    payload = {
        "incident": {
            "title": "",
            "description": "Some description",
            "service": "Payments",
            "severity": "SEV2",
        }
    }
    response = client.post("/suggest", json=payload)
    assert response.status_code == 422


def test_suggest_validation_error_on_title_too_long():
    payload = {
        "incident": {
            "title": "x" * 201,
            "description": "Some description",
            "service": "Payments",
            "severity": "SEV2",
        }
    }
    response = client.post("/suggest", json=payload)
    assert response.status_code == 422


def test_suggest_validation_error_on_empty_description():
    payload = {
        "incident": {
            "title": "Valid title",
            "description": "",
            "service": "Payments",
            "severity": "SEV2",
        }
    }
    response = client.post("/suggest", json=payload)
    assert response.status_code == 422


def test_suggest_validation_error_on_description_too_long():
    payload = {
        "incident": {
            "title": "Valid title",
            "description": "x" * 5001,
            "service": "Payments",
            "severity": "SEV2",
        }
    }
    response = client.post("/suggest", json=payload)
    assert response.status_code == 422


def test_suggest_validation_error_on_missing_service():
    payload = {
        "incident": {
            "title": "Valid title",
            "description": "Valid description",
            "severity": "SEV2",
        }
    }
    response = client.post("/suggest", json=payload)
    assert response.status_code == 422


# ---------------------------------------------------------------------------
# Sprint 2: Ollama Embed Integration Tests (Issue #62)
# ---------------------------------------------------------------------------

def test_embed_returns_numeric_vector_on_success():
    mock_vector = [0.123, -0.456, 0.789, 0.0]
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "model": "nomic-embed-text",
        "embeddings": [mock_vector],
    }
    mock_client.post.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "Checkout service connection timeout"})
        assert response.status_code == 200
        data = response.json()

        assert data["embedding"] == mock_vector
        assert data["model"] == "nomic-embed-text"
        assert all(isinstance(x, (int, float)) for x in data["embedding"])
        mock_client.post.assert_called_once()


def test_embed_defaults_to_document_kind_and_prepends_prefix():
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {"embeddings": [[0.1, 0.2, 0.3]]}
    mock_client.post.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "Service latency spiked on payments"})
        assert response.status_code == 200
        mock_client.post.assert_called_once()
        call_args, call_kwargs = mock_client.post.call_args
        body = call_kwargs["json"]
        assert body["model"] == "nomic-embed-text"
        assert body["input"] == "search_document: Service latency spiked on payments"


def test_embed_query_kind_prepends_search_query_prefix():
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {"embeddings": [[0.4, 0.5, 0.6]]}
    mock_client.post.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.post(
            "/embed",
            json={"text": "checkout timeout", "kind": "query"},
        )
        assert response.status_code == 200
        mock_client.post.assert_called_once()
        _, call_kwargs = mock_client.post.call_args
        assert call_kwargs["json"]["input"] == "search_query: checkout timeout"


def test_embed_validation_error_on_invalid_kind():
    response = client.post("/embed", json={"text": "Hello", "kind": "unknown_kind"})
    assert response.status_code == 422


def test_embed_respects_custom_model_environment_variable(monkeypatch):
    monkeypatch.setenv("OLLAMA_EMBED_MODEL", "all-minilm")
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {"embeddings": [[0.5, 0.6]]}
    mock_client.post.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "Custom model test"})
        assert response.status_code == 200
        data = response.json()
        assert data["model"] == "all-minilm"
        _, call_kwargs = mock_client.post.call_args
        assert call_kwargs["json"]["model"] == "all-minilm"


def test_embed_handles_bad_ollama_url_gracefully(monkeypatch):
    monkeypatch.setenv("OLLAMA_URL", "http://localhost:abc")
    response = client.post("/embed", json={"text": "Testing bad url"})
    assert response.status_code == 503
    assert "cannot reach ollama" in response.json()["detail"].lower()


def test_embed_validation_error_on_empty_text():
    response = client.post("/embed", json={"text": ""})
    assert response.status_code == 422


def test_embed_validation_error_on_missing_body():
    response = client.post("/embed", json={})
    assert response.status_code == 422


def test_embed_validation_error_on_invalid_text_type():
    response = client.post("/embed", json={"text": 12345})
    assert response.status_code == 422


def test_embed_handles_ollama_timeout_gracefully():
    mock_client = AsyncMock()
    mock_client.post.side_effect = httpx.TimeoutException("Read timed out")

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "Some text"})
        assert response.status_code == 503
        assert "timed out" in response.json()["detail"].lower()


def test_embed_handles_ollama_connection_failure_gracefully():
    mock_client = AsyncMock()
    mock_client.post.side_effect = httpx.ConnectError("Failed to connect")

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "Some text"})
        assert response.status_code == 503
        assert "cannot reach ollama" in response.json()["detail"].lower()


def test_embed_handles_ollama_error_status_gracefully():
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 500
    mock_resp.text = "Internal Server Error"
    mock_client.post.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "Some text"})
        assert response.status_code == 502


def test_embed_handles_ollama_malformed_response_gracefully():
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {"unexpected_key": "unexpected_value"}
    mock_client.post.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "Some text"})
        assert response.status_code == 502


def test_embed_rejects_vector_with_booleans():
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "model": "nomic-embed-text",
        "embeddings": [[True, False, 0.5]],
    }
    mock_client.post.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "Boolean vector test"})
        assert response.status_code == 502


def test_embed_rejects_vector_with_nan():
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "model": "nomic-embed-text",
        "embeddings": [[float("nan"), 0.5]],
    }
    mock_client.post.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "NaN vector test"})
        assert response.status_code == 502


def test_embed_rejects_vector_with_inf():
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "model": "nomic-embed-text",
        "embeddings": [[float("inf"), 0.5]],
    }
    mock_client.post.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "Inf vector test"})
        assert response.status_code == 502


def test_embed_rejects_empty_vector():
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "model": "nomic-embed-text",
        "embeddings": [[]],
    }
    mock_client.post.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "Empty vector test"})
        assert response.status_code == 502


def test_embed_rejects_legacy_embedding_key():
    mock_client = AsyncMock()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "model": "nomic-embed-text",
        "embedding": [0.1, 0.2, 0.3],
    }
    mock_client.post.return_value = mock_resp

    with patch("main._get_client", return_value=mock_client):
        response = client.post("/embed", json={"text": "Legacy key test"})
        assert response.status_code == 502


def test_get_ollama_timeout_validation(monkeypatch):
    monkeypatch.setenv("OLLAMA_TIMEOUT", "15.5")
    assert get_ollama_timeout() == 15.5

    monkeypatch.setenv("OLLAMA_TIMEOUT", "0")
    assert get_ollama_timeout() == 10.0

    monkeypatch.setenv("OLLAMA_TIMEOUT", "-5")
    assert get_ollama_timeout() == 10.0

    monkeypatch.setenv("OLLAMA_TIMEOUT", "inf")
    assert get_ollama_timeout() == 10.0

    monkeypatch.setenv("OLLAMA_TIMEOUT", "nan")
    assert get_ollama_timeout() == 10.0

    monkeypatch.setenv("OLLAMA_TIMEOUT", "invalid_string")
    assert get_ollama_timeout() == 10.0
