# AI/ML Roadmap

**Team:** Himanshu, Shlok, Bhakti, and Dhruva. Who builds each part is assigned per sprint.

This file covers everything AI: four AI features, the AI SRE and its evidence, the MCP server,
models, and how we prove that it works. The non-AI features are in [features.md](features.md).

## Rules

These rules come from the requirements and apply to every AI feature.

- **AI suggests; people decide.** AI never changes status, severity, assignment, or SLA
  (DR-10.2).
- **Labelled and cited.** Every AI result is labelled as AI and names the evidence or incidents
  it used (FR-18.2).
- **Every suggestion is accepted or rejected by a person,** and that choice is recorded on the
  timeline (FR-19).
- **The app works without AI.** Each call has a timeout. If AI is down or slow, the incident page
  shows no suggestions, and everything else carries on (FR-18.4, NFR-12).
- **Only the backend calls `ai-service`,** and `ai-service` never writes to the database. Agent
  tools read through our REST API with the caller's permissions.

## Approach: AI without custom ML

We train no models. Each job uses the tool that does it best:

| Job | Tool | Why |
| --- | --- | --- |
| Reasoning, hypotheses, explanations, and drafts | **LLM** | Language and reasoning are what it is good at |
| Finding similar incidents | **A ready-made embedding model** (through Ollama) and vector search | Matches by meaning ("UPI timeouts" ≈ "gateway slow"); no training |
| Spotting abnormal metrics | **Statistics in PromQL:** now against the same hour over the last 7 days | Deterministic and explainable. LLMs are unreliable with long lists of numbers |
| Grouping log lines | **Loki's built-in log patterns** | Loki already mines patterns, so we don't build them |
| Counting, time windows, blast radius | **Plain code** | Exact answers |

The LLM receives facts that are already calculated, such as "error rate 4× the 7-day normal".
It never does arithmetic on raw data.

## Features

### AI-1 Find similar
- **What it does:** turns every incident into a searchable record (title, symptoms, service,
  cause, and fix) and finds the closest matches.
- **Shown in three places:**
  - on the incident page, as "similar past incidents" with a match % and how each was fixed;
  - when declaring an incident, as "this looks like open incident INC-42" (a warning only;
    nothing is merged);
  - in history search, by meaning rather than only by keywords.
- **Sprint:** 4.

### AI-2 AI SRE investigation
- **When it runs:** when an incident is declared (as a queued job), or when someone clicks
  "Investigate".
- **What it does:** collects evidence through the [MCP tools](#mcp-server), then returns:
  - a short **summary** of what is happening;
  - **hypotheses**, ranked by confidence, each with its evidence;
  - **affected services**, from the service map's blast radius;
  - **suggested actions**, each with Accept and Reject. The choice is recorded; nothing runs
    automatically.
- **"Ask the SRE":** questions in plain language inside an incident ("what was the checkout error
  rate in the last hour?"), answered from the same evidence, with charts and log lines.
- **Sprint:** 5.

### AI-3 Drafting
One feature for every writing task. The result is always an editable draft:
- an incident summary ("catch me up");
- a status page update;
- the postmortem first draft, built from the timeline, the accepted hypotheses, and the
  follow-ups.

**Sprint:** 5.

### AI-4 Triage hint
- **What it does:** when someone declares an incident, it suggests a severity and service. It
  works from a written severity guide, similar incidents (AI-1), and the current alerts. The
  person chooses.
- **Sprint:** 5.

## AI SRE evidence

The agent may only use these signals. Each one comes from an MCP tool.

| Signal | What the agent reads | Example evidence line |
| --- | --- | --- |
| Alerts | Source, first and last seen, count, grouping | "14 × payment-gateway 5xx > 5% since 13:56" |
| Monitors and API health | Up or down per region; response time against the 7-day normal; SSL | "Checkout down in 2 of 3 regions for 14 min" |
| Metrics | Error rate, latency, traffic, and saturation (CPU, memory, DB pool, queue depth) against normal | "UPI success rate 99.1% → 81.6% at 13:52" |
| Logs | Patterns that are new since the incident started; top messages; counts against normal | "2,341 × 'upstream timeout after 800ms'" |
| Deploys and config changes | Deploys to this service and to the services it depends on, in the window | "Deploy #482 at 13:51 changed `timeout_ms` 8000 → 800" |
| Service map | Upstream and downstream health; external provider status | "UPI provider status page: no incident" |
| Past incidents | Similar incidents and their confirmed causes (from AI-1) | "INC-1984 (87% match): fixed by rollback" |
| Runbooks | The matching runbook steps | "Runbook: roll back a payments-api deploy" |
| Heartbeats and jobs | Scheduled jobs that are late or missing | "Nightly backup heartbeat missed" |
| The timeline | What people already tried or ruled out | "Rollback of 4.18.0 didn't help (11:47)" |

**Rules for its output:**
- **Evidence:**
  - Every hypothesis cites at least one evidence line, with a link to its source (a log query, a
    chart, or a deploy).
  - No evidence means no hypothesis.
  - Evidence against a hypothesis is shown too ("DB pool usage normal: 41%").
- **Confidence:**
  - Each hypothesis shows a confidence score and the reasoning that links the evidence.
  - A similar past incident supports a cause; it never proves it.
- **Limits:** a maximum number of steps, a time limit, and a token budget per investigation.

## MCP server

- **Who uses it:** engineers, from Claude Desktop, Claude Code, or Cursor.
- **How they connect:** each person uses their own **personal access token**, so the AI tool sees
  only what that person may see.
- **Where it runs:** in `ai-service` (FastMCP, Streamable HTTP). It is a thin adapter: every tool
  calls our REST API with the caller's token, so the backend still checks permissions and writes
  the audit log.

| Tool | Example question | Phase |
| --- | --- | --- |
| `list_incidents`, `get_incident` (with timeline) | "Summarise the open SEV1s" | Read (Sprint 4) |
| `search_incidents` (AI-1) | "Have we seen this error before?" | Read (Sprint 4) |
| `get_service`, `service_map`, `who_is_on_call` | "Who owns payments-api, and what depends on it?" | Read (Sprint 4) |
| `list_alerts`, `monitor_status` | "Is checkout down, and from which regions?" | Read (Sprint 4) |
| `query_logs`, `query_metrics` | "Error logs from checkout in the last 30 minutes" | Read (Sprint 4) |
| `recent_deploys`, `get_runbook`, `get_postmortem` | "What changed in payments-api today?" | Read (Sprint 4) |
| `add_comment`, `acknowledge` | Acknowledge or comment from the editor | Write (Sprint 5) |

**Write tools:**
- run as the user;
- are role-checked;
- are audited as "via MCP".

**Never available through MCP:** changing status, or declaring or closing an incident.

**Our own AI SRE uses the same tools,** so a person in Cursor and the agent see the same evidence.

## Models and hosting

- **Local by default:** Ollama runs the LLM and the embedding model (small models, about 1 GB of
  RAM).
- **Hosted model, optional:** the agent may use a hosted model on a free API tier, off by default
  and only on demo-shop data. This needs NFR-12.1 reworded first (decision D37).
- **Running it:** `ai-service` runs locally or on demand. With `AI_SERVICE_URL` unset, the backend
  skips AI, and the screens hide the AI panels.

## Data and evaluation

We don't need training data; we need evaluation data.

| What | Source |
| --- | --- |
| Investigation scenarios | Each demo-shop fault switch, with its known cause and injection time |
| Incident collection | 30–50 sample incidents, plus about 50 public postmortems from the [danluu/post-mortems](https://github.com/danluu/post-mortems) collection, trimmed to title, symptom, cause, and fix. Each labelled with severity and similar pairs |
| Optional harder checks | Slices of the public Numenta Anomaly Benchmark (metrics) and Loghub (logs) datasets |

| Feature | Measure |
| --- | --- |
| AI-1 Find similar | Precision@3: are the top 3 matches really related? |
| AI-2 AI SRE | How often the real cause ranks first on demo scenarios, and the time to the first hypothesis |
| AI-3 Drafting | A checklist per draft: cites real incidents, invents no IDs, valid structure |
| AI-4 Triage hint | How often the suggested severity matches what people chose |

Demo data is cleaner than real production, and the samples are small. Report results as "about
70% on 40 scenarios", never as general accuracy.

## Optional ML experiment

If the course needs a trained model, train an anomaly detector on demo-shop metrics and compare
it with the statistical baseline (detection delay, false alarms). It stays a report experiment,
not a product dependency.

## By sprint

| Sprint | AI/ML work |
| --- | --- |
| 1 | `ai-service` skeleton, `/health`, stub `/suggest`, pytest, CI job **(done, #21)** |
| 2 | Incident collection and labels; demo-shop scenario table; Ollama with the embedding model |
| 3 | Evidence functions: PromQL baselines, Loki patterns, deploy window; agent tool loop on demo data (#91: Done, PR #181) |
| 4 | AI-1 Find similar; MCP server with the read tools; personal access tokens (with backend) |
| 5 | AI-2 AI SRE with Accept/Reject; AI-3 Drafting; AI-4 Triage hint; MCP write tools |
| 6 | Evaluation runs, the report, and the optional ML experiment |
