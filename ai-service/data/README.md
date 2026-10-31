
# Public Postmortem Dataset

This dataset supports AI-1: Find Similar Incidents.

## Files

- `postmortems.jsonl`: Real incidents collected from public postmortems.
- `test_set.jsonl`: 20 held-out incidents with labels identifying similar incidents.

## Required fields

Each incident contains:

- `id`: Unique incident identifier
- `title`: Incident title
- `summary`: Short summary written in our own words
- `service_type`: Type of affected service
- `cause_category`: Main cause of the incident
- `fix`: How the incident was resolved
- `source_url`: Link to the original public postmortem

## Allowed cause categories

- configuration
- deployment
- database
- network
- hardware
- capacity
- dependency
- software_bug
- security
- human_error
- other

## Data quality rules

- Use real, publicly documented incidents.
- Do not invent incidents or source URLs.
- Every incident must have all required fields.
- Use only the allowed cause categories.
- Keep the 20 test incidents separate from the training dataset.
- Similarity labels must refer to real incident IDs.

## Sprint 4 - Retrieval Evaluation (Issue #161)

### Objective

Evaluate how well Ollama embeddings retrieve related incidents using cosine similarity.

### Evaluation setup

- Embedding model: `nomic-embed-text`
- Dataset: 100 public postmortem incidents
- Test set: 20 labelled queries
- Retrieval: Top 3 incidents ranked by cosine similarity
- Metric: Mean Precision@3
- Script: `ai-service/retrieval_eval.py`

### Text templates compared

1. **Title only:** Incident title.
2. **Full:** Title, summary (symptoms), cause category, and fix.

### Measured results

| Template | Mean Precision@3 |
|---|---|
| Title only | 13.33% (8/60 relevant matches) |
| Full | 18.33% (11/60 relevant matches) |

The full template performed 5 percentage points better on the 20 labelled test cases.

### Similarity threshold comparison

These results measure precision among matches accepted from the top 3 results at each threshold.

| Threshold | Title only | Full |
|---|---|---|
| 0.40 | 8/60 (13.33%) | 11/60 (18.33%) |
| 0.50 | 8/60 (13.33%) | 11/60 (18.33%) |
| 0.60 | 8/60 (13.33%) | 11/60 (18.33%) |
| 0.70 | 7/21 (33.33%) | 9/35 (25.71%) |

### Selected configuration

- **Text template:** Full
- **Cosine similarity threshold:** 0.70
- **Embedding model:** `nomic-embed-text`

The full template achieved the higher mean Precision@3.

At threshold 0.70, the full template accepted 35 of the 60 top-3 matches. Of these, 9 were labelled relevant, giving 25.71% precision among accepted matches.

The threshold improves precision among accepted matches compared with lower tested thresholds, but it also rejects 2 relevant matches. Further validation is needed before production use.

### Limitations

These results apply only to this dataset and its 20 labelled test cases. They do not represent general model accuracy or production performance.

### Reproduce the evaluation

From the `ai-service` directory:

    python retrieval_eval.py
    python -m pytest tests/test_retrieval_eval.py -v

The evaluation requires Ollama running locally with `nomic-embed-text` installed. The pytest tests use fixed vectors and do not require Ollama.
