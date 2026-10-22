# Security Policy

Settle is a student project (IT314, Group 2). We still treat security problems seriously.

## Reporting a vulnerability

**Do not open a public issue or pull request for a security problem.** Report it privately:

1. Go to the repository's **Security** tab.
2. Choose **Report a vulnerability**, and describe what you found and how to reproduce it.

Only the maintainer sees the report. We aim to reply within 3 days and to fix confirmed problems in
the next sprint, or sooner for anything serious.

Only the latest `main` and the deployed app are supported.

## If you committed a secret

Tell Shane straight away, even if you deleted it in a later commit: the old commit still has it.
The secret must be **rotated** (a new value set wherever it is used), because removing it from Git
history does not make it safe again.

## Rules every change follows

- **Nothing real in the repository.** Credentials come from environment variables (on Render for
  production). `.env.example` holds placeholders only, and `.env` is never committed.
- **No secrets in logs.** Passwords, tokens, and webhook secrets never reach a log line (NFR-04.2).
- **The server checks every request.** The browser only hides controls; Spring Security checks the
  token and the role on every API call (NFR-04.1).
- **HTTPS outside local development** (NFR-04.3). Vercel and Render provide it.

## What is in place

| Protection | Status |
| --- | --- |
| Passwords stored as salted hashes (bcrypt, through Spring Security's delegating encoder) | Done |
| JWT login; logout and role changes invalidate old tokens through a token version | Done |
| Role checks on every endpoint, covered by `SecurityMatrixTest` | In progress (#48) |
| CodeQL code scanning on every pull request | Done |
| Dependabot alerts and weekly dependency updates | Done |
| One hashed, revocable token per alert-sending tool (D31) | Planned (#68) |
| Rate limits on login, alert webhooks, and personal access tokens (D42, NFR-04.4) | Planned (#69) |
