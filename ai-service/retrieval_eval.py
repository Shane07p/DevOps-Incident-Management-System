
"""Evaluate similar-incident retrieval using Ollama embeddings."""

import json
import math
import os
from pathlib import Path
from main import get_ollama_timeout

import httpx

DATA_DIR = Path(__file__).resolve().parent / "data"
OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434").rstrip("/")
MODEL = os.getenv("OLLAMA_EMBED_MODEL", "nomic-embed-text")


def load_jsonl(path):
    with path.open("r", encoding="utf-8") as file:
        return [json.loads(line) for line in file if line.strip()]


def incident_text(incident, template):
    if template == "title":
        return incident["title"]

    if template == "full":
        return "\n".join(
            [
                f"Title: {incident['title']}",
                f"Symptoms: {incident.get('summary', '')}",
                f"Cause: {incident.get('cause_category', '')}",
                f"Fix: {incident.get('fix', '')}",
            ]
        )

    raise ValueError(f"Unknown template: {template}")


def cosine_similarity(a, b):
    if not a or len(a) != len(b):
        raise ValueError("Vectors must be nonempty and have equal dimensions")

    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))

    if norm_a == 0 or norm_b == 0:
        return 0.0

    return dot / (norm_a * norm_b)


def precision_at_3(retrieved_ids, relevant_ids):
    relevant = set(relevant_ids)
    return sum(item in relevant for item in retrieved_ids[:3]) / 3


def embed(client, text, kind):
    prefix = "search_query: " if kind == "query" else "search_document: "
    response = client.post(
        f"{OLLAMA_URL}/api/embed",
        json={"model": MODEL, "input": prefix + text},
        timeout=get_ollama_timeout(),
    )
    response.raise_for_status()
    vector = response.json()["embeddings"][0]
    if not vector or not all(
        isinstance(x, (int, float)) and not isinstance(x, bool)
        and math.isfinite(x) for x in vector
    ):
        raise ValueError("Invalid embedding vector")
    return vector


def evaluate(incidents, cases, template, client):
    document_vectors = {
        item["id"]: embed(client, incident_text(item, template), "document")
        for item in incidents
    }

    results = []
    for case in cases:
        query_vector = embed(client, case["query"], "query")

        ranked = sorted(
            (
                (incident_id, cosine_similarity(query_vector, vector))
                for incident_id, vector in document_vectors.items()
            ),
            key=lambda pair: (-pair[1], pair[0]),
        )

        top_three = ranked[:3]
        score = precision_at_3(
            [incident_id for incident_id, _ in top_three],
            case["similar_incident_ids"],
        )

        results.append(
            {
                "case": case["id"],
                "precision_at_3": score,
                "top_3": top_three,
            }
        )

    average = sum(result["precision_at_3"] for result in results) / len(results)
    return average, results


def main():
    incidents = load_jsonl(DATA_DIR / "postmortems.jsonl")
    cases = load_jsonl(DATA_DIR / "test_set.jsonl")

    if not incidents or not cases:
        raise ValueError("Both datasets must contain records")

    print(f"Model: {MODEL}")
    print(f"Incidents: {len(incidents)} | Test cases: {len(cases)}")

    with httpx.Client() as client:
        for template in ("title", "full"):
            print(f"\nEvaluating template: {template}", flush=True)
            average, results = evaluate(incidents, cases, template, client)

            for result in results:
                matches = [item[0] for item in result["top_3"]]
                print(
                    f"{result['case']}: {matches} "
                    f"P@3={result['precision_at_3']:.3f}"
                )

            print(f"Mean Precision@3 ({template}): {average:.2%}")
            print("Threshold comparison:")
            for threshold in (0.40, 0.50, 0.60, 0.70):
                accepted = [
                    (case["case"], incident_id)
                    for case in results
                    for incident_id, similarity in case["top_3"]
                    if similarity >= threshold
                ]
                total = len(accepted)
                relevant = sum(
                    incident_id in set(test_case["similar_incident_ids"])
                    for case_id, incident_id in accepted
                    for test_case in cases
                    if test_case["id"] == case_id
                )
                precision = relevant / total if total else 0.0
                print(
                    f"  Threshold {threshold:.2f}: "
                    f"{relevant}/{total} accepted matches relevant, "
                    f"precision={precision:.2%}"
                )

if __name__ == "__main__":
    main()
