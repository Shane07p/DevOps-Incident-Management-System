# AGENTS.md

Conventions for anyone working in this repository, human or coding agent.

## What this project is

An incident management web application built for IT314 by Group 2. The specification lives in
`docs/`. Treat those documents as the source of truth and read them before changing behaviour:

| File | Why you would open it |
| --- | --- |
| `docs/PROJECT.md` | Scope, stack, team, and the decision log (D02, D04, …) |
| `docs/PROJECT_STRUCTURE.md` | Architecture, data model, class responsibilities, and the incident rules |
| `docs/REQUIREMENTS.md` | FR/NFR summary and domain rules |
| `docs/user-stories.md` | User stories US-01 to US-20 with acceptance criteria |
| `docs/epics-and-conflicts.md` | Epics, survey findings, and stakeholder conflict resolutions |
| `docs/FEATURES.md` | Every feature, how it is used, its requirement, and its sprint |
| `docs/sprint-plan.md` | Sprint roadmap and sprint records |
| `docs/TESTING_AND_WORKFLOW.md` | The essential-tests table, traceability rules, and the workflow |
| `docs/testing/` | The test plan, the designed test cases with their IDs, and the test report per sprint |
| `docs/TRACEABILITY.csv` | Every FR sub-ID mapped to its planned check and current status |
| `docs/plans/` | Sprint implementation plans (who does what) and the AI/ML roadmap |
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
- **Our tables live in the `incident` schema.** Write migrations with unqualified names (`CREATE TABLE app_user`, not `public.app_user`); they land in `incident` automatically.
- **One error shape.** Throw `NotFoundException`, `ConflictException`, or `FieldValidationException`; `ApiExceptionHandler` turns them into `ApiError`. Never add a catch-all `Exception` handler: it hides Spring Security's 403.
- **Document every endpoint.** `@Tag` on the controller and `@Operation(summary = "…")` on each method, so Swagger UI stays useful.
- **Do not return JPA entities from controllers.** Use request and response DTOs.
- **AI never changes state.** It suggests; deterministic code handles status, SLA, and escalation.
- **Only the backend calls `ai-service`,** which never writes to the database. The app must keep working when it is down or slow.

## Code layout

Inside `backend/src/main/java/com/group2/incident/`, group by feature first: `auth`, `catalog`,
`incident`, `alert`, `notification`, `postmortem`. Keep SLA and escalation classes with the
incident feature for now. Inside each feature, one folder per layer:

```
auth/
├── controller/   AuthController
├── dto/          LoginRequest, LoginResponse, UserResponse (records)
├── entity/       AppUser, Role
├── repository/   AppUserRepository
├── service/      AuthService
└── security/     only where a feature needs it
common/
├── config/       OpenApiConfig, TimeConfig
└── error/        ApiError, ApiExceptionHandler, NotFoundException, …
```

Controllers receive requests, services apply rules, repositories read and write. Do not add an
interface for a service that has one implementation. Tests mirror the same folders
(`auth/controller/AuthControllerTest`); shared test setup such as `@IntegrationTest` lives in
`support/`, and database schema tests in `db/`.

**Lombok, a small subset only.** Use `@Getter` and `@NoArgsConstructor(access = PROTECTED)` on
entities, `@RequiredArgsConstructor` for constructor injection, and `@Slf4j` for loggers. DTOs
are Java records, not Lombok classes. Never put `@Data`, `@EqualsAndHashCode`, `@ToString`, or
`@Builder` on an entity: generated equality and `toString` over every field break Hibernate's
lazy loading. Change entity state through named methods (`revokeTokens()`), not `@Setter`.

**Frontend** (`frontend/src/`): pages in `pages/`, the app shell in `components/shell/`, Settle pieces
in `components/app/`, shadcn primitives in `components/ui/`, and animated third-party components in
`components/effects/`. Style with the design tokens from `index.css` (`bg-sev-1-bg`,
`text-status-open`), not raw colours. Add components with the shadcn CLI, including React Bits,
Aceternity, and Magic UI through their registries. See `frontend/README.md`.

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

Integration tests use the `@IntegrationTest` annotation (full app, Testcontainers PostgreSQL,
rolled back after each test). Don't add `src/test/resources/application.properties`: it replaces
the main config during tests instead of adding to it.

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
- **Install frontend packages with `npm ci`**, which follows the lockfile exactly. Use `npm install` only when changing dependencies. If `package-lock.json` conflicts in a merge, take either side and run `npm install` to regenerate it; never edit it by hand.

## Deployment

`main` deploys itself once CI passes: the frontend to Vercel, the backend to Render (database on
Supabase). A change that needs a new setting means a new environment variable on Render, never a
value committed to the repository.

## Secrets

Nothing real goes in the repository. Credentials come from the environment; `.env.example`
carries placeholders only. Passwords, API tokens, and webhook secrets must not reach logs.

## Git

Short-lived branches off `main`, one focused pull request per story, reviewed and merged by Shane,
merge when CI passes. Before your first commit, check that `git config user.email` is an address
verified on your GitHub account, or your commits won't be credited to you. Keep commits attributable to their actual author. Do not add
`Co-Authored-By` lines for AI tools.

**Work in this repository, never in a fork.** Every team member is a collaborator with write
access, so forks are not needed, and they fall behind `main` fast. Before any work, run
`git remote get-url origin`. It must be `https://github.com/Shane07p/DevOps-Incident-Management-System`
(with or without `.git`). If it points to a fork:

1. Re-point it: `git remote set-url origin https://github.com/Shane07p/DevOps-Incident-Management-System.git`.
2. Push any unmerged branch from the fork to this repository, and open its pull request here.
3. Tell the user to delete the fork on GitHub (fork → Settings → Danger Zone). Agents must not
   delete it themselves.

Never open a pull request from a fork.

**Start from the latest `main`, every time.** Before starting or resuming any work:

```
git fetch origin
git checkout main
git pull --ff-only
git checkout -b feature/<issue-number>-short-name   # new work
git checkout <branch> && git rebase origin/main      # or: continue an existing branch
```

After rebasing a branch that is already pushed, update it with `git push --force-with-lease`. That
is the only force-push allowed: on your own feature branch, never on `main` or someone else's
branch. If `git pull --ff-only` fails because local `main` has its own commits, or a rebase
conflicts, stop and ask the user. Never reset, or resolve a conflict by discarding someone's work.

**Label every pull request** with the labels of its issue, including at least one area:
`backend`, `frontend`, `ai-ml`, `devops`, or `documentation`. Add `bug` for a fix, `test` for work
that is mostly tests, and `dependencies` for version bumps. Put `Closes #<issue>` in the
description so the issue closes on merge. With the GitHub CLI, add labels when opening the pull
request (`gh pr create --label backend …`) or afterwards (`gh pr edit <number> --add-label backend`).

## Before you finish a change

1. The acceptance criteria of the story are met.
2. `./mvnw verify` and `npm test` pass, and you have seen the output.
3. New schema arrived as a new migration.
4. Affected documents in `docs/` are updated, including `TRACEABILITY.csv` if a check now runs.
5. If the change depends on an Open decision in the `docs/PROJECT.md` log, that decision is
   recorded first with its date and reference. Do not quietly invent the answer.
