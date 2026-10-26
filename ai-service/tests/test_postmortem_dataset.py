
import json
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parents[1] / "data"

REQUIRED_FIELDS = {
    "id", "title", "summary", "service_type",
    "cause_category", "fix", "source_url"
}

CATEGORIES = {
    "configuration", "deployment", "database",
    "network", "hardware", "capacity", "dependency",
    "software_bug", "security", "human_error", "other"
}


def load_jsonl(filename):
    path = DATA_DIR / filename
    assert path.exists(), f"Missing file: {path}"

    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.strip():
            rows.append(json.loads(line))
    return rows


def test_postmortems():
    incidents = load_jsonl("postmortems.jsonl")
    assert len(incidents) >= 100

    ids = set()
    for incident in incidents:
        assert REQUIRED_FIELDS <= incident.keys()
        assert all(
            isinstance(incident[field], str) and incident[field].strip()
            for field in REQUIRED_FIELDS
        )
        assert incident["cause_category"] in CATEGORIES
        assert incident["source_url"].startswith(("http://", "https://"))
        assert incident["id"] not in ids
        ids.add(incident["id"])


def test_similarity_labels():
    incidents = load_jsonl("postmortems.jsonl")
    test_cases = load_jsonl("test_set.jsonl")

    assert len(test_cases) == 20
    known_ids = {row["id"] for row in incidents}
    test_ids = set()

    for case in test_cases:
        assert "id" in case
        assert "similar_incident_ids" in case
        assert case["id"] not in test_ids
        test_ids.add(case["id"])

        assert isinstance(case["similar_incident_ids"], list)
        assert case["similar_incident_ids"]
        assert all(
            isinstance(ref, str) and ref in known_ids and ref != case["id"]
            for ref in case["similar_incident_ids"]
        )
