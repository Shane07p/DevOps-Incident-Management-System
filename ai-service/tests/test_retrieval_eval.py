
import pytest

from retrieval_eval import (
    cosine_similarity,
    incident_text,
    precision_at_3,
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
