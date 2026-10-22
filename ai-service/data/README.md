
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
