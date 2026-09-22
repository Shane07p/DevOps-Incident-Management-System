# AGENTS.md

Conventions for anyone working in this repository, human or coding agent.

## What this project is

An incident management web application built for IT314 by Group 2. The specification lives in
`docs/`. Treat those documents as the source of truth and read them before changing behaviour:

| File | Why you would open it |
| --- | --- |
| `docs/PROJECT.md` | Scope, stack, team, and the decision log (D02, D04, …) |
| `docs/PROJECT_STRUCTURE.md` | Architecture, data model, class responsibilities, and the incident rules |
| `docs/REQUIREMENTS_AND_STORIES.md` | FR/NFR summary, domain rules, and starter user stories |
| `docs/SPRINT_AND_TEST_PLAN.md` | Build order, the essential-tests table, and the workflow |
| `docs/TRACEABILITY.csv` | Every FR sub-ID mapped to its planned check and current status |
| `docs/requirements/Group-2.pdf` | The full submitted requirements document |

## Stack

Java 21 (build target) on Spring Boot 4.1.1, Spring Data JPA, Spring Security, Validation,
PostgreSQL 16 with Flyway. React 19 with TypeScript, Vite, TanStack Query, Zod, and Tailwind.
Tests use JUnit, Testcontainers, PIT, Vitest, and Cypress.

Spring Boot 4 renamed several starters. It is `spring-boot-starter-webmvc`, not
`-web`; `spring-boot-starter-flyway`, not the bare Flyway artifact; and the test starters are
split per module (`spring-boot-starter-data-jpa-test` and friends). Follow Boot 4 documentation
when an older tutorial disagrees, and do not fall back to 3.x.

## Rules that are easy to get wrong

These come from the design documents. Breaking one silently breaks a planned test.

- **Backend is the authority.** Browser validation is a convenience; the server re-checks every
  request, including permissions.
- **UTC everywhere.** Store and compare timestamps in UTC. Display zone is a separate concern.
  `IncidentManagementApplication` sets the JVM default to UTC in a static block; leave it there.
  Without it the JDBC driver sends the local zone id, and PostgreSQL rejects legacy aliases such
  as `Asia/Calcutta` outright.
- **Flyway owns the schema.** JPA runs with `ddl-auto=validate`. Never let Hibernate create or
  update tables. New schema means a new migration file, never an edit to an applied one.
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
- **Inject `Clock`** into any code that reads the time, so tests can control it.
- **Do not return JPA entities from controllers.** Use request and response DTOs.
- **AI never changes state.** It suggests; deterministic code handles status, SLA, and escalation.

## Code layout

Inside `backend/src/main/java/com/group2/incident/`, group by feature: `auth`, `catalog`,
`incident`, `alert`, `notification`, `postmortem`. Keep SLA and escalation classes with the
incident feature for now. Each feature holds its own controller, DTOs, service, entity, and
repository. Controllers receive requests, services apply rules, repositories read and write. Do
not add an interface for a service that has one implementation.

REST endpoints live under `/api`. Use separate endpoints for status, assignment, and severity
changes so their rules stay independent.

## Testing

Pick the level that can actually verify the behaviour, and do not repeat the same rule at three
levels:

| Behaviour | Level |
| --- | --- |
| Transition rules, SLA arithmetic, fixed targets | JUnit with a controlled `Clock` |
| Duplicate alerts, concurrent acknowledgement, restart with an overdue escalation | Testcontainers against PostgreSQL |
| Roles and rejected alert signatures | API security tests |
| One complete incident flow | A single Cypress scenario |
| Meaningful form behaviour | Vitest |
| Transition and SLA rules | PIT, on demand |

Before claiming something works, run it and read the output. `docs/TRACEABILITY.csv` moves to
Passed or Failed only after the check has actually run, with the date and commit recorded.

## Local environment notes

- **The database listens on 5433, not 5432.** A locally installed PostgreSQL service often owns
  5432 on a developer machine and silently shadows the container, producing a password failure
  that looks like a credential bug. Change the port only through `DB_PORT`.
- **`.env` is read by Docker Compose, not by Spring.** Running the backend with `./mvnw
  spring-boot:run` picks up real environment variables, so export them if you change any default.
  The defaults in `application.properties` already match the Compose defaults, so the untouched
  path works with no configuration.
- **The database password is fixed on first start.** PostgreSQL only applies `POSTGRES_PASSWORD`
  when it initialises an empty data directory. After changing it, run `docker compose down -v` to
  drop the volume, which also deletes local data.

## Secrets

Nothing real goes in the repository. Credentials come from the environment; `.env.example`
carries placeholders only. Passwords, API tokens, and webhook secrets must not reach logs.

## Git

Short-lived branches off `main`, one focused pull request per story, review by another member,
merge when CI passes. Keep commits attributable to their actual author. Do not add
`Co-Authored-By` lines for AI tools.

## Before you finish a change

1. The acceptance criteria of the story are met.
2. `./mvnw verify` and `npm test` pass, and you have seen the output.
3. New schema arrived as a new migration.
4. Affected documents in `docs/` are updated, including `TRACEABILITY.csv` if a check now runs.
5. If the change depends on an Open decision in the `docs/PROJECT.md` log, that decision is
   recorded first with its date and reference. Do not quietly invent the answer.
