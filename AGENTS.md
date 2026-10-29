# AGENTS.md

Rules for anyone working in this repository, human or coding agent. Each part of the code has its
own rules next to it: `backend/AGENTS.md`, `frontend/AGENTS.md`, and `ai-service/AGENTS.md`. Read
the one for the part you change as well as this file.

## The project

Settle, an incident management web application for IT314 (Group 2). The specification in `docs/`
is the source of truth: start with `docs/PROJECT_STRUCTURE.md` for the architecture and incident
rules, and `docs/PROJECT.md` for scope and the decision log. Each issue links the parts it needs.

## Domain rules

These come from the design documents. Breaking one silently breaks a planned test.

- **Backend is the authority.** Browser validation is a convenience; the server re-checks every
  request, including permissions.
- **UTC everywhere.** Store and compare timestamps in UTC. The display zone is a separate concern (D12).
- **SLA targets are copied at incident creation and stay fixed.** A later severity or service
  change does not alter them. Adding an alert or reassigning does not reset either clock.
- **Both clocks start at creation.** The response clock stops at the first accepted
  acknowledgement; the resolution clock stops at RESOLVED. Acknowledgement does not stop the
  resolution clock.
- **Exceeding a target is a breach; reaching it exactly is not.**
- **Escalation notifies, it does not reassign.** The first accepted acknowledger becomes the
  assignee. Escalation must still force an Incident version increment so an acknowledgement race
  rolls the loser back.
- **Comments are separate timeline inserts.** They do not require or increment the Incident
  version, so two people can comment at once. They stay allowed on CLOSED incidents.
- **Timeline and audit entries are append-only through the application.** Correct an entry by
  adding an attributed amendment linked to the original.
- **Alerts group only by an explicit source/service correlation key.** A shared service name or
  similar text is not enough. Uniqueness on `(source, event_id)` is the final duplicate guard.
- **Only these transitions are legal:**
  `OPEN → ACKNOWLEDGED → INVESTIGATING → IDENTIFIED → MONITORING → RESOLVED → CLOSED`, plus
  `INVESTIGATING → MONITORING` and `MONITORING → INVESTIGATING`. Reject anything else.
- **AI never changes state.** It suggests; deterministic code handles status, SLA, and escalation.
- **Only the backend calls `ai-service`,** which never writes to the database. The app must keep
  working when it is down or slow.

## Decisions

If a change depends on an Open decision in the `docs/PROJECT.md` log, record it there first, with
its date and reference. Do not quietly invent the answer.

## Secrets and deployment

Nothing real goes in the repository. Credentials come from the environment; `.env.example`
carries placeholders only. Passwords, API tokens, and webhook secrets must not reach logs.

`main` deploys itself once CI passes: the frontend to Vercel, the backend to Render (database on
Supabase). A new setting means a new environment variable on Render, and a placeholder for it in
`.env.example`, never a value committed to the repository.

## Git

Work in this repository, never in a fork. Before any work, `git remote get-url origin` must be
`https://github.com/Shane07p/DevOps-Incident-Management-System` (with or without `.git`). If it
points to a fork, re-point it with `git remote set-url origin <that URL>.git`, push any unmerged
branch here and open its pull request here, and tell the user to delete the fork on GitHub. Agents
must not delete it themselves.

Start from the latest `main`, every time:

```
git fetch origin
git checkout main
git pull --ff-only
git checkout -b feature/<issue-number>-short-name   # new work
git checkout <branch> && git rebase origin/main      # or: continue an existing branch
```

- `git push --force-with-lease` after rebasing your own pushed branch is the only force-push
  allowed: never on `main` or someone else's branch.
- If `git pull --ff-only` fails or a rebase conflicts, stop and ask the user. Never reset, or
  resolve a conflict by discarding someone's work.
- One focused pull request per issue, reviewed and merged by Shane once CI passes.
- Commits are credited to their author: `git config user.email` must be verified on your GitHub
  account.
- Label every pull request with its issue's labels, including one area (`backend`, `frontend`,
  `ai-ml`, `devops`, or `documentation`); add `bug` for a fix, `test` for mostly tests, and
  `dependencies` for version bumps (`gh pr create --label backend …`).
- The description says `Closes #<issue>` and pastes the test output.

## Before you finish a change

1. The acceptance criteria of the issue are met.
2. The tests for the part you changed pass, and you have read the output. Before claiming
   something works, run it.
3. Affected documents in `docs/` are updated. A row in `docs/testing/TEST_CASES.md` or
   `docs/TRACEABILITY.csv` says Passed only after its check has run, with the date and commit.
