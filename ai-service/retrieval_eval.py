
"""Evaluate similar-incident retrieval using the AI service /embed API."""

import json
import math
import os
from pathlib import Path

import httpx
from main import get_ollama_timeout

DATA_DIR = Path(__file__).resolve().parent / "data"
AI_SERVICE_URL = os.getenv(
    "AI_SERVICE_URL", "http://127.0.0.1:8001"
).rstrip("/")
MODEL = os.getenv("OLLAMA_EMBED_MODEL", "nomic-embed-text")

THRESHOLDS = (0.60, 0.65, 0.70, 0.75, 0.80, 0.85)
TEMPLATES = ("title", "full", "symptoms_service")


def load_jsonl(path):
    with path.open("r", encoding="utf-8") as file:
        return [
            json.loads(line)
            for line in file
            if line.strip()
        ]


def incident_text(incident, template):
    if template == "title":
        return incident["title"]

    if template == "full":
        return "\n".join([
            f"Title: {incident['title']}",
            f"Symptoms: {incident.get('summary', '')}",
            f"Cause: {incident.get('cause_category', '')}",
            f"Fix: {incident.get('fix', '')}",
        ])

    if template == "symptoms_service":
        service = incident.get(
            "service_type",
            incident.get("service", "")
        )
        return "\n".join([
            f"Title: {incident['title']}",
            f"Symptoms: {incident.get('summary', '')}",
            f"Service: {service}",
        ])

    raise ValueError(f"Unknown template: {template}")


def cosine_similarity(a, b):
    if not a or len(a) != len(b):
        raise ValueError(
            "Vectors must be nonempty and have equal dimensions"
        )

    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))

    if norm_a == 0 or norm_b == 0:
        return 0.0

    return dot / (norm_a * norm_b)


def precision_at_3(retrieved_ids, relevant_ids):
    relevant = set(relevant_ids)
    return sum(
        item in relevant
        for item in retrieved_ids[:3]
    ) / 3


def embed(client, text, kind):
    response = client.post(
        f"{AI_SERVICE_URL}/embed",
        json={"text": text, "kind": kind},
        timeout=get_ollama_timeout(),
    )
    response.raise_for_status()

    data = response.json()

    actual_model = data.get("model")
    if actual_model is not None and actual_model != MODEL:
        raise ValueError(
            f"Embedding model mismatch: expected {MODEL}, "
            f"got {actual_model}"
        )

    vector = data["embedding"]

    if not vector or not all(
        isinstance(x, (int, float))
        and not isinstance(x, bool)
        and math.isfinite(x)
        for x in vector
    ):
        raise ValueError("Invalid embedding vector")

    return vector


def evaluate(incidents, cases, template, client):
    document_vectors = {
        item["id"]: embed(
            client,
            incident_text(item, template),
            "document",
        )
        for item in incidents
    }

    results = []

    for case in cases:
        query_vector = embed(
            client,
            case["query"],
            "query",
        )

        ranked = sorted(
            (
                (
                    incident_id,
                    cosine_similarity(query_vector, vector),
                )
                for incident_id, vector in document_vectors.items()
            ),
            key=lambda pair: (-pair[1], pair[0]),
        )

        top_three = ranked[:3]

        score = precision_at_3(
            [incident_id for incident_id, _ in top_three],
            case["similar_incident_ids"],
        )

        results.append({
            "case": case["id"],
            "precision_at_3": score,
            "top_3": top_three,
        })

    average = (
        sum(result["precision_at_3"] for result in results)
        / len(results)
    )

    return average, results


def threshold_stats(results, cases, threshold):
    relevant_by_case = {
        case["id"]: set(case["similar_incident_ids"])
        for case in cases
    }

    accepted = 0
    relevant = 0

    for result in results:
        case_id = result["case"]

        for incident_id, similarity in result["top_3"]:
            if similarity >= threshold:
                accepted += 1

                if incident_id in relevant_by_case[case_id]:
                    relevant += 1

    precision = relevant / accepted if accepted else None

    return {
        "threshold": threshold,
        "accepted": accepted,
        "relevant": relevant,
        "precision": precision,
    }


def main():
    incidents = load_jsonl(
        DATA_DIR / "postmortems.jsonl"
    )
    cases = load_jsonl(
        DATA_DIR / "test_set.jsonl"
    )

    if not incidents or not cases:
        raise ValueError(
            "Both datasets must contain records"
        )

    print(f"Expected embedding model: {MODEL}")
    print(f"AI service: {AI_SERVICE_URL}")
    print(
        f"Incidents: {len(incidents)} | "
        f"Test cases: {len(cases)}"
    )

    with httpx.Client() as client:
        for template in TEMPLATES:
            print(
                f"\nEvaluating template: {template}",
                flush=True,
            )

            average, results = evaluate(
                incidents,
                cases,
                template,
                client,
            )

            for result in results:
                matches = [
                    item[0]
                    for item in result["top_3"]
                ]

                print(
                    f"{result['case']}: {matches} "
                    f"P@3={result['precision_at_3']:.3f}"
                )

            print(
                f"Mean Precision@3 ({template}): "
                f"{average:.2%}"
            )

            print("Threshold comparison:")

            for threshold in THRESHOLDS:
                stats = threshold_stats(
                    results,
                    cases,
                    threshold,
                )

                precision_text = (
                    f"{stats['precision']:.2%}"
                    if stats["precision"] is not None
                    else "N/A"
                )

                print(
                    f"  Threshold {threshold:.2f}: "
                    f"{stats['relevant']}/"
                    f"{stats['accepted']} accepted matches "
                    f"relevant, "
                    f"precision={precision_text}"
                )


if __name__ == "__main__":
    main()
