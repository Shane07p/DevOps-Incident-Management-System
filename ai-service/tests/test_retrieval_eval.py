
import httpx
import pytest

from retrieval_eval import (
    cosine_similarity,
    embed,
    evaluate,
    incident_text,
    precision_at_3,
    threshold_stats,
)


def test_cosine_identical():
    assert cosine_similarity([1, 2], [1, 2]) == pytest.approx(1.0)


def test_cosine_opposite():
    assert cosine_similarity([1, 0], [-1, 0]) == pytest.approx(-1.0)


def test_cosine_orthogonal():
    assert cosine_similarity([1, 0], [0, 1]) == pytest.approx(0.0)


def test_cosine_zero_vector():
    assert cosine_similarity([0, 0], [1, 0]) == 0.0


def test_cosine_invalid_dimensions():
    with pytest.raises(ValueError):
        cosine_similarity([1, 2], [1])


def test_precision_at_3():
    result = precision_at_3(
        ["A", "B", "C"],
        ["A", "C"],
    )
    assert result == pytest.approx(2 / 3)


def test_full_template():
    incident = {
        "title": "Database outage",
        "summary": "Queries failed",
        "cause_category": "database",
        "fix": "Restarted database",
    }

    text = incident_text(incident, "full")

    assert "Database outage" in text
    assert "Queries failed" in text
    assert "database" in text
    assert "Restarted database" in text


def test_symptoms_service_template():
    incident = {
        "title": "API outage",
        "summary": "Requests timed out",
        "service_type": "api",
    }

    text = incident_text(incident, "symptoms_service")

    assert "API outage" in text
    assert "Requests timed out" in text
    assert "Service: api" in text


def test_embed_uses_shared_api(monkeypatch):
    monkeypatch.setattr(
        "retrieval_eval.AI_SERVICE_URL",
        "http://testserver",
    )

    def handler(request):
        assert request.url.path == "/embed"
        assert request.method == "POST"
        assert request.read() == (
            b'{"text":"example","kind":"query"}'
        )

        return httpx.Response(
            200,
            json={
                "embedding": [1.0, 0.0],
                "model": "nomic-embed-text",
            },
        )

    with httpx.Client(
        transport=httpx.MockTransport(handler)
    ) as client:
        assert embed(
            client, "example", "query"
        ) == [1.0, 0.0]


def test_evaluate_ranking_ties_and_top_three(monkeypatch):
    vectors = {
        "query": [1.0, 0.0],
        "A": [1.0, 0.0],
        "B": [0.8, 0.6],
        "C": [0.8, 0.6],
        "D": [0.0, 1.0],
    }

    def handler(request):
        import json

        body = json.loads(request.content)
        text = body["text"]

        return httpx.Response(
            200,
            json={
                "embedding": vectors[text],
                "model": "nomic-embed-text",
            },
        )

    monkeypatch.setattr(
        "retrieval_eval.AI_SERVICE_URL",
        "http://testserver",
    )

    incidents = [
        {"id": "D", "title": "D"},
        {"id": "C", "title": "C"},
        {"id": "B", "title": "B"},
        {"id": "A", "title": "A"},
    ]

    cases = [
        {
            "id": "TEST-1",
            "query": "query",
            "similar_incident_ids": ["A", "C"],
        }
    ]

    with httpx.Client(
        transport=httpx.MockTransport(handler)
    ) as client:
        average, results = evaluate(
            incidents,
            cases,
            "title",
            client,
        )

    assert len(results) == 1

    ranked_ids = [
        incident_id
        for incident_id, _ in results[0]["top_3"]
    ]

    assert ranked_ids == ["A", "B", "C"]

    assert results[0]["precision_at_3"] == pytest.approx(2 / 3)
    assert average == pytest.approx(2 / 3)


def test_threshold_stats():
    results = [
        {
            "case": "TEST-1",
            "top_3": [
                ("A", 0.90),
                ("B", 0.80),
                ("C", 0.70),
            ],
        }
    ]

    cases = [
        {
            "id": "TEST-1",
            "similar_incident_ids": ["A", "C"],
        }
    ]

    stats = threshold_stats(results, cases, 0.75)

    assert stats["accepted"] == 2
    assert stats["relevant"] == 1
    assert stats["precision"] == pytest.approx(0.5)


def test_threshold_stats_no_matches():
    results = [
        {
            "case": "TEST-1",
            "top_3": [("A", 0.60)],
        }
    ]

    cases = [
        {
            "id": "TEST-1",
            "similar_incident_ids": ["A"],
        }
    ]

    stats = threshold_stats(results, cases, 0.85)

    assert stats["accepted"] == 0
    assert stats["relevant"] == 0
    assert stats["precision"] is None
