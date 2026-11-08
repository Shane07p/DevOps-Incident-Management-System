# Docs index

Every document, what it holds, and when to open it. Open only what the task needs: a long file read
end to end crowds out what matters. To change code, read [AGENTS.md](../AGENTS.md) and the
`AGENTS.md` of the part you change. Before opening a pull request, read [lessons.md](lessons.md).

## Rules and how we work

| File | Holds | Open it when |
| --- | --- | --- |
| [../AGENTS.md](../AGENTS.md) | Project-wide rules: domain rules, decisions, secrets, git, finishing a change | Before any change |
| [../backend/AGENTS.md](../backend/AGENTS.md), [../frontend/AGENTS.md](../frontend/AGENTS.md), [../ai-service/AGENTS.md](../ai-service/AGENTS.md) | The rules of each part | Before changing that part |
| [lessons.md](lessons.md) | Mistakes we have made, and the rule each one taught | Before opening a pull request |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | Issues, branches, pull requests, review | Starting or handing in work |
| [TESTING_AND_WORKFLOW.md](TESTING_AND_WORKFLOW.md) | How we test, track, and review; traceability statuses | Deciding what to test, or updating a status |
| [../README.md](../README.md) | Run, test, and deploy; live links | Running the app or its tests |
| [../SECURITY.md](../SECURITY.md) | Reporting a vulnerability | A security problem |

## Product and requirements

| File | Holds | Open it when |
| --- | --- | --- |
| [PROJECT.md](PROJECT.md) | Product, scope, stack, team, and the decision log (D1, D2, …) | Checking scope, who owns what, or a decision |
| [REQUIREMENTS.md](REQUIREMENTS.md) | FRs, NFRs, and DRs by ID (current version) | A task names an FR, NFR, or DR |
| [requirements/Group-2.pdf](requirements/Group-2.pdf) | The submitted SRS | Comparing with what was submitted |
| [FEATURES.md](FEATURES.md) | Every feature, how it is used, its requirement, and its sprint | Building or explaining a feature |
| [user-stories.md](user-stories.md) | User stories with acceptance criteria | A task names a US ID |
| [epics-and-conflicts.md](epics-and-conflicts.md) | Epics, survey findings, stakeholder conflicts | Questions about why a requirement exists |

## Architecture and design

| File | Holds | Open it when |
| --- | --- | --- |
| [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) | Architecture, the data model, and the incident rules (§1–§10) | Any backend change to incidents, SLAs, escalation, alerts, or orgs |
| [plans/2026-11-02-organizations-design.md](plans/2026-11-02-organizations-design.md) | How orgs and sign-up work (D48) | Anything touching `org_id`, sign-up, or memberships |
| [plans/ai-ml-roadmap.md](plans/ai-ml-roadmap.md) | The AI features, the AI SRE and its evidence, the MCP server | Any `ai-service` work |
| [plans/demo-shop-scenarios.md](plans/demo-shop-scenarios.md) | The five fault switches and what each should show | Demo-shop or AI SRE evidence work |
| [diagrams/](diagrams/) | HLD (`hld.png`, source `hld.eraser`) and the Lab 6 activity diagram | Explaining the architecture |

## Plans and progress

| File | Holds | Open it when |
| --- | --- | --- |
| [plans/2026-10-17-implementation-plan.md](plans/2026-10-17-implementation-plan.md) | Who builds what, per sprint, with each issue's status and contract | Finding an issue's owner, contract, or proposed endpoints |
| [sprint-plan.md](sprint-plan.md) | Roadmap and sprint records | Sprint scope or end-of-sprint results |
| [plans/done/](plans/done/) | Finished step-by-step plans, kept for the record | Rarely: only to see how finished work was planned |

## Testing

| File | Holds | Open it when |
| --- | --- | --- |
| [testing/TEST_PLAN.md](testing/TEST_PLAN.md) | What is tested, at which level, by whom | Choosing how to test something |
| [testing/TEST_CASES.md](testing/TEST_CASES.md) | Every test case by ID, grouped by area under `###` headings | Adding or updating a test case: search for its `###` area rather than reading it all |
| [testing/TEST_REPORT.md](testing/TEST_REPORT.md) | Real results per sprint | Recording or quoting results |
| [TRACEABILITY.csv](TRACEABILITY.csv) | Each FR sub-ID, its check, and its status | Marking a check Passed (with date and commit) |

`scripts/check_docs.py` runs in CI: it fails on a broken link or `#anchor`, a Passed row without a date
and commit, and a test case ID in the traceability file with no row in the test cases. Run it with
`python scripts/check_docs.py` before pushing docs changes.
