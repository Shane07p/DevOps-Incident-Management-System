# Docs

The documents for Settle, IT314 Group 2, in the order of the software lifecycle: requirements,
design, project management, and testing. The specification here is the source of truth for the code.

## Deliverables

| Deliverable | Where | Notes |
| --- | --- | --- |
| Software requirements specification | [1-requirements/srs.md](1-requirements/srs.md) | Current FRs, NFRs, and DRs; the submitted version is [Group-2.pdf](1-requirements/Group-2.pdf) |
| User stories | [1-requirements/user-stories.md](1-requirements/user-stories.md) | With acceptance criteria |
| Elicitation, epics, and conflicts | [1-requirements/epics-and-conflicts.md](1-requirements/epics-and-conflicts.md) | Survey findings and how conflicts were resolved |
| Architecture and design | [2-design/architecture.md](2-design/architecture.md) | Components, data model, and the incident rules |
| Diagrams | [2-design/diagrams/](2-design/diagrams/) | High-level design (`hld.png`), Lab 6 activity diagram |
| Features | [2-design/features.md](2-design/features.md) | Every feature, how it is used, its requirement, and its sprint |
| Project guide and decision log | [3-project-management/project.md](3-project-management/project.md) | Scope, stack, team, and every decision (D1 onward) |
| Sprint plan and records | [3-project-management/sprint-plan.md](3-project-management/sprint-plan.md) | Roadmap and what each sprint delivered |
| Implementation plan | [3-project-management/implementation-plan.md](3-project-management/implementation-plan.md) | Who builds what, with each issue's status |
| Test plan | [4-testing/test-plan.md](4-testing/test-plan.md) | What is tested, at which level, by whom |
| Test cases | [4-testing/test-cases.md](4-testing/test-cases.md) | Every case by ID, with technique and status |
| Test report | [4-testing/test-report.md](4-testing/test-report.md) | Real results per sprint |
| Traceability | [4-testing/traceability.csv](4-testing/traceability.csv) | Each requirement, its check, and its status |

## Every document, and when to open it

Open only what the task needs: a long file read end to end crowds out what matters. To change code,
read [AGENTS.md](../AGENTS.md) and the `AGENTS.md` of the part you change. Before opening a pull
request, read [lessons.md](lessons.md).

### Rules and how we work

| File | Holds | Open it when |
| --- | --- | --- |
| [../AGENTS.md](../AGENTS.md) | Project-wide rules: domain rules, decisions, secrets, git, finishing a change | Before any change |
| [../backend/AGENTS.md](../backend/AGENTS.md), [../frontend/AGENTS.md](../frontend/AGENTS.md), [../ai-service/AGENTS.md](../ai-service/AGENTS.md) | The rules of each part | Before changing that part |
| [lessons.md](lessons.md) | Mistakes we have made, and the rule each one taught | Before opening a pull request |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | Issues, branches, pull requests, review | Starting or handing in work |
| [4-testing/testing-and-workflow.md](4-testing/testing-and-workflow.md) | How we test, track, and review; traceability statuses | Deciding what to test, or updating a status |
| [../README.md](../README.md) | Run, test, and deploy; live links | Running the app or its tests |
| [../SECURITY.md](../SECURITY.md) | Reporting a vulnerability | A security problem |

### 1. Requirements

| File | Holds | Open it when |
| --- | --- | --- |
| [1-requirements/srs.md](1-requirements/srs.md) | FRs, NFRs, and DRs by ID | A task names an FR, NFR, or DR |
| [1-requirements/user-stories.md](1-requirements/user-stories.md) | User stories with acceptance criteria | A task names a US ID |
| [1-requirements/epics-and-conflicts.md](1-requirements/epics-and-conflicts.md) | Epics, survey findings, stakeholder conflicts | Asking why a requirement exists |

### 2. Design

| File | Holds | Open it when |
| --- | --- | --- |
| [2-design/architecture.md](2-design/architecture.md) | Architecture, data model, and the incident rules (§1–§10) | Any backend change to incidents, SLAs, escalation, alerts, or orgs |
| [2-design/features.md](2-design/features.md) | Every feature, how it is used, its requirement, and its sprint | Building or explaining a feature |
| [2-design/organizations-design.md](2-design/organizations-design.md) | How orgs and sign-up work (D48) | Anything touching `org_id`, sign-up, or memberships |
| [2-design/ai-ml-roadmap.md](2-design/ai-ml-roadmap.md) | The AI features, the AI SRE and its evidence, the MCP server | Any `ai-service` work |
| [2-design/demo-shop-scenarios.md](2-design/demo-shop-scenarios.md) | The five fault switches and what each should show | Demo-shop or AI SRE evidence work |

### 3. Project management

| File | Holds | Open it when |
| --- | --- | --- |
| [3-project-management/project.md](3-project-management/project.md) | Product, scope, stack, team, and the decision log | Checking scope, who owns what, or a decision |
| [3-project-management/implementation-plan.md](3-project-management/implementation-plan.md) | Who builds what, per sprint, with each issue's status and contract | Finding an issue's owner, contract, or proposed endpoints |
| [3-project-management/sprint-plan.md](3-project-management/sprint-plan.md) | Roadmap and sprint records | Sprint scope or end-of-sprint results |
| [3-project-management/done/](3-project-management/done/) | Finished step-by-step plans, kept for the record | Rarely: only to see how finished work was planned |

### 4. Testing

| File | Holds | Open it when |
| --- | --- | --- |
| [4-testing/test-plan.md](4-testing/test-plan.md) | What is tested, at which level, by whom | Choosing how to test something |
| [4-testing/test-cases.md](4-testing/test-cases.md) | Every test case by ID, grouped by area under `###` headings | Adding or updating a test case: search for its `###` area rather than reading it all |
| [4-testing/test-report.md](4-testing/test-report.md) | Real results per sprint | Recording or quoting results |
| [4-testing/traceability.csv](4-testing/traceability.csv) | Each FR sub-ID, its check, and its status | Marking a check Passed (with date and commit) |

`scripts/check_docs.py` runs in CI: it fails on a broken link or `#anchor`, a Passed row without a date
and commit, and a test case ID in the traceability file with no row in the test cases. Run it with
`python scripts/check_docs.py` before pushing docs changes.
