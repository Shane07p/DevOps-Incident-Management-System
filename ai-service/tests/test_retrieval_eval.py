
import httpx
import pytest

from retrieval_eval import (
    cosine_similarity,
    incident_text,
    precision_at_3,
    embed,
    evaluate,
    threshold_stats,
)


def test_identical_vectors():
    assert cosine_similarity([1, 0], [1, 0]) == pytest.approx(1.0)


def test_opposite_vectors():
    assert cosine_similarity([1, 0], [-1, 0]) == pytest.approx(-1.0)


def test_orthogonal_vectors():
    assert cosine_similarity([1, 0], [0, 1]) == pytest.approx(0.0)


def test_zero_vector():
    assert cosine_similarity([0, 0], [1, 0]) == 0.0


def test_invalid_vector_dimensions():
    with pytest.raises(ValueError):
        cosine_similarity([1, 0], [1])


def test_precision_at_3():
    retrieved = ["PM-001", "PM-002", "PM-003"]
    relevant = ["PM-001", "PM-003", "PM-009"]
    assert precision_at_3(retrieved, relevant) == pytest.approx(2 / 3)


def test_full_template():
    incident = {
        "title": "Database outage",
        "summary": "Queries timed out",
        "cause_category": "configuration",
        "fix": "Restore configuration",
    }

    text = incident_text(incident, "full")
    assert "Database outage" in text
    assert "Queries timed out" in text
    assert "configuration" in text
    assert "Restore configuration" in text


def test_symptoms_service_template():
    incident = {
        "title": "DNS outage",
        "summary": "DNS requests timed out",
        "service_type": "dns",
    }

    text = incident_text(incident, "symptoms_service")
    assert "DNS outage" in text
    assert "DNS requests timed out" in text
    assert "Service: dns" in text


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
            json={"embedding": [1.0, 0.0], "model": "test"},
        )

    with httpx.Client(
        transport=httpx.MockTransport(handler)
    ) as client:
        assert embed(client, "example", "query") == [1.0, 0.0]


def test_evaluate_ranking_ties_and_top_three(monkeypatch):
    vectors = {
        "query": [1.0, 0.0],
        "A": [1.0, 0.0],
        "B": [0.8, 0.6],
        "C": [0.8, 0.6],
        "D": [0.0, 1.0],
    }

    def handler(request):
        body = __import__("json").loads(request.content)
        text = body["text"]
        return httpx.Response(
            200,
            json={
                "embedding": vectors[text],
                "model": "test",
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
            incidents, cases, "title", client
        )

    assert [item[0] for item in results[0]["top_3"]] == [
        "A", "B", "C"
    ]
    assert len(results[0]["top_3"]) == 3
    assert average == pytest.approx(2 / 3)


def test_threshold_stats():
    results = [
        {
            "case": "TEST-1",
            "top_3": [
                ("A", 0.90),
                ("B", 0.75),
                ("C", 0.60),
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
    assert stats["precision"] == 0.0
