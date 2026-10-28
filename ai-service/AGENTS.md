# AI service rules

Python with FastAPI, running models through Ollama. The project-wide rules are in the root
`AGENTS.md`; setup is in `README.md`. Run `pytest` before every pull request.

- **Suggest, never act.** The service returns suggestions and evidence. It never writes to the
  database and never changes an incident's status, SLA, or escalation.
- **Only the backend calls it.** The app must keep working when the service is down or slow, so
  every call has a timeout (`OLLAMA_TIMEOUT`).
- **Self-hosted models only** (D28, NFR-12.1). Incident data never goes to an external AI
  provider.
- **Credentials stay in the backend.** The Grafana token never reaches this service; it gets
  logs and metrics through the backend, or recorded fixtures in tests.
- **Pin exact versions** in `requirements.txt` (`==`), so builds repeat and Dependabot can track
  them.
- **CI has no model.** Tests use the stub or recorded data, never a live Ollama.
