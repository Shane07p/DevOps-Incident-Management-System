
# Public Postmortem Dataset

This dataset supports AI-1: Find Similar Incidents.

## Files

- `postmortems.jsonl`: Real incidents collected from public postmortems.
- `test_set.jsonl`: 20 held-out test cases with labels identifying similar incidents.

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
- Keep the 20 test cases separate from the retrieval dataset.
- Similarity labels must refer to real incident IDs.

## Sprint 4 - Retrieval Evaluation (Issue #161)

### Objective

Evaluate how well Ollama embeddings retrieve related incidents using cosine similarity.

The evaluation measures retrieval performance on a small labelled test set. It does not measure general model accuracy.

### Evaluation setup

- Embedding model: `nomic-embed-text`
- Dataset: 100 public postmortem incidents
- Test set: 20 labelled queries
- Retrieval: Top 3 incidents ranked by cosine similarity
- Metric: Mean Precision@3
- Script: `ai-service/retrieval_eval.py`
- Embedding API: Existing AI service `POST /embed`

The evaluation uses the same embedding endpoint as the AI backend, avoiding duplicated Ollama embedding logic.

### Text templates compared

Three templates were evaluated:

1. **Title only:** Incident title.
2. **Full:** Title, summary (symptoms), cause category, and fix.
3. **Symptoms + service:** Title, summary (symptoms), and service type.

### Measured results

| Template | Relevant matches | Mean Precision@3 |
|---|---|---|
| Title only | 8/60 | 13.33% |
| Full | 11/60 | 18.33% |
| Symptoms + service | 11/60 | 18.33% |

The full and symptoms + service templates both achieved 18.33% Mean Precision@3.

Both performed 5 percentage points better than title-only on these 20 labelled test cases.

Adding service type did not improve overall Mean Precision@3 compared with the full template.

### Similarity threshold comparison

The threshold determines which of the top 3 retrieved incidents are accepted based on cosine similarity.

Each cell shows:

Relevant matches / accepted matches (precision).

| Threshold | Title only | Full | Symptoms + service |
|---|---|---|---|
| 0.60 | 8/60 (13.33%) | 11/60 (18.33%) | 11/60 (18.33%) |
| 0.65 | 8/39 (20.51%) | 11/57 (19.30%) | 11/58 (18.97%) |
| 0.70 | 7/21 (33.33%) | 9/35 (25.71%) | 8/30 (26.67%) |
| 0.75 | 4/4 (100.00%) | 5/18 (27.78%) | 5/14 (35.71%) |
| 0.80 | 1/1 (100.00%) | 3/4 (75.00%) | 2/3 (66.67%) |
| 0.85 | 0/0 (N/A) | 1/1 (100.00%) | 1/1 (100.00%) |

At 0.85, the title-only template accepted no matches, so its accepted-match precision is undefined.

Higher thresholds generally reduce the number of accepted suggestions.

A high precision percentage based on only one or a few accepted matches is not strong evidence of reliable retrieval.

### Selected configuration

- **Text template:** Full
- **Cosine similarity threshold:** 0.70 (provisional)
- **Embedding model:** `nomic-embed-text`

The full template was retained because it achieved 18.33% Mean Precision@3 and preserved more relevant matches at threshold 0.70 than the symptoms + service template.

At threshold 0.70:

- Full: 9 relevant matches out of 35 accepted (25.71% precision).
- Symptoms + service: 8 relevant matches out of 30 accepted (26.67% precision).
- Title only: 7 relevant matches out of 21 accepted (33.33% precision).

Although title-only and symptoms + service have slightly higher accepted-match precision at 0.70, the full template retains more labelled relevant matches.

The selection prioritizes retrieval coverage alongside Mean Precision@3, rather than accepted-match precision alone.

The threshold is provisional and should be validated on more labelled data before production use.

### Implications for backend Issue #159

The current retrieval quality is not reliable enough for automatic related-incident recommendations.

Only 11 of 60 unfiltered top-3 suggestions were labelled relevant (18.33%).

Even with the selected full template and threshold 0.70, only 9 of 35 accepted suggestions were labelled relevant (25.71%).

This means the backend implementation in Issue #159 may frequently return unrelated incidents.

Therefore:

- Treat retrieved incidents as experimental AI-generated suggestions.
- Do not automatically change incident status, severity, or assignment based on these results.
- Require human review before accepting suggestions.
- Show source incident IDs so users can verify the matches.
- Improve retrieval quality and evaluate on more labelled cases before production deployment.

The selected configuration is an experimental baseline, not proof that the retrieval system is production-ready.

### Selection rationale and trade-offs

The full and symptoms + service templates tied at 18.33% Mean Precision@3.

At threshold 0.75, symptoms + service achieved higher accepted-match precision (35.71%) than full (27.78%), but both retained only 5 relevant matches.

At threshold 0.80, the full template accepted only 4 matches across the 20 test queries.

Very strict thresholds can make precision appear high while rejecting most suggestions.

The full template at 0.70 was retained as a provisional balance between accepting relevant matches and filtering weaker similarities.

Further experiments are needed to determine whether another template, threshold, or retrieval approach performs better.

### Limitations

- Evaluation uses only 100 incidents and 20 labelled test cases.
- Results may differ on other incident datasets.
- Similarity labels may not capture every genuinely related incident.
- Thresholds were evaluated only from 0.60 to 0.85.
- The new template did not improve overall Mean Precision@3.
- These results are not general model accuracy or production performance.

### Reproduce the evaluation

Install and run Ollama with the `nomic-embed-text` model available.

Start the AI service from the `ai-service` directory:

```powershell
python -m uvicorn main:app --host 127.0.0.1 --port 8001
```

In a second terminal, from the same directory, run:

```powershell
python retrieval_eval.py
```

The evaluation uses the existing `POST /embed` endpoint.

By default, the script connects to:

`http://127.0.0.1:8001`

To use another AI service address, set the `AI_SERVICE_URL` environment variable.

For example, in PowerShell:

```powershell
$env:AI_SERVICE_URL = "http://127.0.0.1:8001"
python retrieval_eval.py
```

### Automated tests

Run the retrieval evaluation tests:

```powershell
python -m pytest tests/test_retrieval_eval.py -q
```

Verified retrieval test result:

```text
12 passed in 0.72s
```

The tests cover:

- Cosine similarity
- Precision@3
- Full and symptoms + service templates
- Calls to the shared embedding API
- Ranking order
- Deterministic tie-breaking
- Top-3 selection
- Threshold counting and precision
- Cases where no matches pass the threshold

Tests use fixed vectors and mocked HTTP responses.

They do not require Ollama or a running AI service.

To run the complete AI service test suite:

```powershell
python -m pytest -q
```
