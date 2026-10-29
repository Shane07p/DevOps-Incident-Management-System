# Backend rules

Java 21 on Spring Boot 4.1, Spring Data JPA, Spring Security, PostgreSQL 16 with Flyway. The
project-wide rules are in the root `AGENTS.md`. Run `./mvnw verify` (needs Docker for
Testcontainers) before every pull request.

`ArchitectureTest` checks some of the rules below on every build: entities have no setters or
generated equality, controllers return no entities, every endpoint is documented, time comes from
the injected `Clock`, and nothing uses Jackson 2.

## Spring Boot 4, not 3

- Starters were renamed: `spring-boot-starter-webmvc`, not `-web`; `spring-boot-starter-flyway`,
  not the bare Flyway artifact; test starters are split per module
  (`spring-boot-starter-data-jpa-test` and friends). Follow Boot 4 docs over older tutorials.
- JSON is Jackson 3 (`tools.jackson.databind`). Jackson 2 (`com.fasterxml.jackson.databind`) is
  only on the classpath through other libraries; don't import it.

## Layout

- Inside `src/main/java/com/group2/incident/`, group by feature first (`auth`, `catalog`,
  `incident`, `alert`, `audit`, `notification`), then one folder per layer: `controller/`, `dto/`,
  `entity/`, `repository/`, `service/`, and `security/` only where a feature needs it. Shared code
  lives in `common/config/` and `common/error/`. SLA and escalation stay with `incident`.
- Controllers receive requests, services apply rules, repositories read and write. No interface
  for a service with one implementation.
- REST endpoints live under `/api`. Status, assignment, and severity changes get separate
  endpoints so their rules stay independent.

## Code

- **Lombok, a small subset only:** `@Getter` and `@NoArgsConstructor(access = PROTECTED)` on
  entities, `@RequiredArgsConstructor` for injection, `@Slf4j` for loggers. DTOs are records.
  Never `@Data`, `@Setter`, `@EqualsAndHashCode`, `@ToString`, or `@Builder` on an entity:
  generated equality and `toString` break Hibernate's lazy loading. Change entity state through
  named methods (`revokeTokens()`, `assignTo(user)`).
- **One error shape.** Throw `NotFoundException`, `ConflictException`, `FieldValidationException`,
  `UnauthorizedException`, or `RateLimitExceededException`; `ApiExceptionHandler` turns them into
  `ApiError`. Exceptions thrown
  in a `HandlerInterceptor` reach it too. Never add a catch-all `Exception` handler: it hides
  Spring Security's 403.
- **Controllers return DTOs**, never JPA entities. `@Tag` on each controller and
  `@Operation(summary = "…")` on each endpoint. Every new endpoint gets its row in
  `SecurityMatrixTest`, which fails otherwise.
- **Inject `Clock`** wherever code reads the time. `IncidentManagementApplication` sets the JVM
  default zone to UTC in a static block; leave it there, or the JDBC driver sends the local zone
  and PostgreSQL rejects aliases such as `Asia/Calcutta`.
- **No test-only code in production classes:** no constructor that takes an `id`, no clock
  setters. Tests use repositories, fixtures, or `ReflectionTestUtils`.

## Database

- Flyway owns the schema; JPA runs with `ddl-auto=validate`. New schema is a new migration file,
  never an edit to one already on `main` (CI rejects that).
- Before adding `V<n>__…`, check the highest number on `main`, and rebase before merge: two
  pull requests with the same number break the second.
- Our tables live in the `incident` schema. Write unqualified names (`CREATE TABLE app_user`).

## Tests

Pick the level that can actually verify the behaviour, and do not repeat one rule at three levels:

| Behaviour | Level |
| --- | --- |
| Transition rules, SLA arithmetic, fixed targets | JUnit with a controlled `Clock` |
| Duplicates, concurrency, restart with an overdue escalation | Testcontainers against PostgreSQL |
| Roles and rejected tokens | API security tests |
| Transition and SLA rules | PIT, not in CI: on demand and at the end of each sprint (`./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`) |

- `@IntegrationTest` runs the full app on Testcontainers PostgreSQL and rolls back after each
  test. A test of a rollback or of two requests at once must run outside that transaction
  (`@Transactional(propagation = NOT_SUPPORTED)`) and delete what it saved; see
  `IncidentCreationRollbackTest`.
- A test must be able to fail. Don't mock the query or rule under test; a boundary test runs the
  real query against PostgreSQL (see `OnCallServiceTest`).
- To stub a `@MockitoSpyBean` of a `@Transactional` bean outside a transaction, stub
  `AopTestUtils.getUltimateTargetObject(bean)`.
- Tests mirror the source folders (`auth/controller/AuthControllerTest`); shared setup lives in
  `support/`, schema tests in `db/`.
- Don't add `src/test/resources/application.properties`: it replaces the main config instead of
  adding to it.

## Local environment

- The database listens on 5433, not 5432: a local PostgreSQL service often owns 5432 and causes a
  password failure that looks like a credential bug. Change it only through `DB_PORT`.
- `.env` is read by Docker Compose, not by Spring; `./mvnw spring-boot:run` reads real
  environment variables. The defaults already match Compose.
- PostgreSQL applies `POSTGRES_PASSWORD` only to an empty data directory. After changing it, run
  `docker compose down -v`, which also deletes local data.
