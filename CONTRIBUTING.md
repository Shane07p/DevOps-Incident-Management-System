# Contributing

How the team works on Settle. The full rules (code layout, incident rules, tests, Git) are in
[AGENTS.md](AGENTS.md); this page is the short version. To run the app, see the [README](README.md).

## Set up once

1. **Clone this repository, not a fork.** Every team member has write access, and forks fall behind
   `main` fast.
   ```
   git clone https://github.com/Shane07p/DevOps-Incident-Management-System.git
   ```
2. **Check your commit email.** `git config user.email` must be an email verified on the GitHub
   account you use for this team, or your commits are credited to someone else (or no one).
3. **Install the GitHub CLI** and log in, so you and your AI agent can read issues, reviews, and CI logs:
   ```
   winget install --id GitHub.cli      # Windows; Mac: brew install gh
   gh auth login
   gh repo set-default Shane07p/DevOps-Incident-Management-System
   ```
4. **Install Docker Desktop.** The backend's integration tests start PostgreSQL in Docker.

## For every issue

1. **Take your assigned issue.** `gh issue list --assignee @me`. It lists the acceptance criteria,
   the contract to follow, and the [test case IDs](docs/testing/TEST_CASES.md) you must automate.
2. **Start from the latest `main`.**
   ```
   git checkout main && git pull
   git checkout -b feature/<issue-number>-short-name
   ```
3. **Backend: post the API shape first.** Put the request and response JSON in your issue before
   coding, so the frontend can build against it.
4. **Open a draft PR early**, with `Closes #<issue>` and the issue's labels (`backend`, `frontend`,
   `ai-ml`, `devops`, or `documentation`).
5. **Write your own tests** for every test case ID in the issue.
6. **Run the checks and paste the output in the PR:**
   ```
   cd backend && ./mvnw verify
   cd frontend && npm run lint && npm run typecheck && npm test
   ```
7. **Mark the PR ready.** CI must be green. Shane reviews, usually the same day.
8. **Fix review comments on the same branch.** If `main` moved on, rebase and push:
   ```
   git fetch origin && git rebase origin/main
   git push --force-with-lease
   ```
   Force-push only your own branch, never `main`.

Shane merges with **Squash and merge**, so your branch becomes one commit on `main`, credited to you.

## Commit messages

Short, in the imperative, with a type and an optional scope, as in the history:

```
feat(catalog): add the service catalog API (#49)
fix(audit): roll the audit entry back with the caller
docs: add the test plan
```

Types: `feat`, `fix`, `test`, `docs`, `refactor`, `chore`, `ci`.

## Using AI coding agents

Claude Code, Cursor, Copilot, Gemini, and Antigravity read [AGENTS.md](AGENTS.md) (through
[CLAUDE.md](CLAUDE.md) and [GEMINI.md](GEMINI.md) where needed). You are still responsible for what
they produce:

- Read the whole diff before you push it.
- Make sure the tests actually check something; a test that passes whatever the code does is worse
  than none.
- Keep the PR description true to what is in the PR.
- Never paste passwords, tokens, or `.env` contents into a chat.

## Getting help

Ask in Slack: `#dev-backend`, `#dev-frontend`, or `#dev-ai`. Stuck for more than an hour? Say so in
your channel rather than waiting. Post a daily update in `#standup`.

Security problems go through [SECURITY.md](SECURITY.md), not a public issue.
