# Organizations and Sign-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Part 1 of [the organizations design](../../2-design/organizations-design.md): anyone signs up and creates an org, signs in by email, and sees only their org's data.

**Architecture:** A request-scoped `OrgContext` holds the current org. Hibernate's `CurrentTenantIdentifierResolver` reads it when a session opens, and `@TenantId` on every org-owned entity adds `org_id` to every read (including `findById`) and fills it on every write. `organization`, `app_user`, and `membership` stay global; the token carries the org and the role comes from the membership.

**Tech Stack:** Spring Boot 4.1.2, Hibernate 7.4 (`@TenantId`, `CurrentTenantIdentifierResolver<Long>`), Spring Security resource server (HS256 JWT), Flyway, PostgreSQL 16, Testcontainers; React 19, TanStack Query, Zod 4, react-hook-form, Vitest.

## Global Constraints

- Org data is filtered by `org_id`; `organization`, `app_user`, and `membership` are global (D48).
- Reading org data with no org set returns nothing and cannot write (the resolver answers `OrgContext.NONE`); only the escalation worker, the outbox relay, and the two credential lookups run as root.
- `service_sla_target` gets no `org_id`: it is an `@ElementCollection` of `ServiceEntity` and is only read through its service.
- Sign-in name is the email, unique regardless of case. Password rules stay 8 to 72 characters and at most 72 bytes (`@MaxBytes`).
- Unknown email, wrong password, and a disabled membership give the same 401.
- Each org numbers its incidents from INC-1; existing incidents keep their numbers.
- Tool token hashes stay unique across all orgs.
- New migrations take the next free `V<n>` on `main` when you start; never edit one already on `main`.
- `backend/AGENTS.md` rules apply: Lombok subset only, DTOs are records, `Clock` for time, errors through `ApiExceptionHandler`, a `SecurityMatrixTest` row per endpoint.
- Frontend: Zod-validated API responses, `showRequestErrors` for field errors, no username anywhere.

## File Structure

**Backend, new**

| File | Responsibility |
| --- | --- |
| `common/tenancy/OrgContext.java` | Thread-local current org: an org id, `ROOT`, or `NONE`; `runAs`/`runAsRoot` helpers that restore the previous value |
| `common/tenancy/OrgTenantResolver.java` | Hibernate `CurrentTenantIdentifierResolver<Long>` reading `OrgContext` |
| `common/tenancy/TenancyConfig.java` | Registers the resolver with Hibernate through `HibernatePropertiesCustomizer` |
| `common/tenancy/OrgContextFilter.java` | Sets the org from the authenticated token for one request, then restores |
| `org/entity/Organization.java`, `org/repository/OrganizationRepository.java` | The org row, its slug, its incident counter |
| `org/entity/Membership.java`, `org/repository/MembershipRepository.java` | Person, org, role, enabled |
| `org/service/Slugs.java` | Turns an org name into a slug |
| `org/service/SignupService.java`, `org/controller/SignupController.java`, `org/dto/SignupRequest.java`, `org/dto/OrgResponse.java` | `POST /api/auth/signup` |
| `db/migration/V10__organizations.sql` | Orgs, memberships, `org_id` columns, emails, incident numbers, per-org unique keys |
| `db/migration/V11__drop_usernames.sql` | Drops `username`, `role`, `enabled` from `app_user` once nothing reads them |

**Backend, changed:** every org-owned entity (13 classes), `AppUser`, `Role` (unchanged enum, now on `Membership`), `AuthService`, `AuthController`, `LoginRequest`, `LoginResponse`, `UserResponse`, `CreateUserRequest`, `AppUserDetailsService`, `AppUserRepository`, `JwtUserConverter`, `SecurityConfig`, `UserAdminService`, `UserAdminController`, `ToolService`, `ToolTokenInterceptor`, `IncidentService`, `Incident`, `AlertService`, `EscalationWorker`, `EscalationTaskRepository`, `OutboxRelay`, `R__dev_seed.sql`, `application.properties`. Deleted: `BootstrapAdmin.java`, `BootstrapAdminTest.java`.

**Backend tests, new:** `support/TestOrgExecutionListener.java`, `support/TestAccounts.java`, `common/tenancy/OrgContextTest.java`, `common/tenancy/TenantFilteringTest.java`, `org/SignupControllerTest.java`, `org/OrgIsolationTest.java`, `db/OrganizationsMigrationTest.java`.

**Frontend, changed or new:** `api/schemas.ts`, `api/auth.ts`, `api/users.ts`, `auth/AuthProvider.tsx`, `pages/LoginPage.tsx`, `pages/SignupPage.tsx` (new), `router.tsx`, `components/shell/TopBar.tsx`, `pages/UsersPage.tsx`, `pages/users/CreateUserDialog.tsx`, and their tests.

**Docs:** `backend/AGENTS.md`, `README.md`, `.env.example`, `docs/1-requirements/user-stories.md` (US-31), `docs/1-requirements/srs.md` (FR-01.4 to FR-01.6), `docs/4-testing/traceability.csv`, `docs/4-testing/test-cases.md`, `docs/2-design/architecture.md`.

Paths below are relative to `backend/src/main/java/com/group2/incident/` (main) and `backend/src/test/java/com/group2/incident/` (test) unless they start with `backend/`, `frontend/`, or `docs/`.

---

### Task 1: Org context and the Hibernate tenant resolver

**Files:**
- Create: `common/tenancy/OrgContext.java`, `common/tenancy/OrgTenantResolver.java`, `common/tenancy/TenancyConfig.java`
- Test: `common/tenancy/OrgContextTest.java` (plain JUnit)

**Interfaces:**
- Produces: `OrgContext.current(): long`, `OrgContext.set(long)`, `OrgContext.clear()`, `OrgContext.runAs(long, Supplier<T>): T`, `OrgContext.runAsRoot(Supplier<T>): T`, constants `OrgContext.ROOT = 0L`, `OrgContext.NONE = -1L`; bean `OrgTenantResolver`.

- [ ] **Step 1: Write the failing test**

```java
package com.group2.incident.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OrgContextTest {

	private final OrgTenantResolver resolver = new OrgTenantResolver();

	@AfterEach
	void clear() {
		OrgContext.clear();
	}

	@Test
	void withNoOrgTheResolverAnswersNoneWhichMatchesNoRow() {
		assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(OrgContext.NONE);
		assertThat(resolver.isRoot(OrgContext.NONE)).isFalse();
	}

	@Test
	void runAsSetsTheOrgAndRestoresThePreviousOne() {
		OrgContext.set(7L);
		long inside = OrgContext.runAs(9L, OrgContext::current);
		assertThat(inside).isEqualTo(9L);
		assertThat(OrgContext.current()).isEqualTo(7L);
	}

	@Test
	void runAsRootIsRootAndRestores() {
		long inside = OrgContext.runAsRoot(OrgContext::current);
		assertThat(resolver.isRoot(inside)).isTrue();
		assertThat(OrgContext.current()).isEqualTo(OrgContext.NONE);
	}

	@Test
	void restoresEvenWhenTheWorkThrows() {
		OrgContext.set(3L);
		try {
			OrgContext.runAs(4L, () -> {
				throw new IllegalStateException("boom");
			});
		}
		catch (IllegalStateException expected) {
			// restored below
		}
		assertThat(OrgContext.current()).isEqualTo(3L);
	}

}
```

- [ ] **Step 2: Run it and see it fail**

Run: `cd backend && ./mvnw -q test -Dtest=OrgContextTest`
Expected: compilation failure, `OrgContext` and `OrgTenantResolver` do not exist.

- [ ] **Step 3: Write `OrgContext`**

```java
package com.group2.incident.common.tenancy;

import java.util.function.Supplier;

/**
 * The org the current thread works for (D48). Hibernate reads it when a session opens, through
 * {@link OrgTenantResolver}, so set it before a transaction starts. {@link #NONE} matches no row and
 * cannot be written; {@link #ROOT} sees every org and is only for the workers and credential lookups.
 */
public final class OrgContext {

	public static final long ROOT = 0L;

	public static final long NONE = -1L;

	private static final ThreadLocal<Long> CURRENT = ThreadLocal.withInitial(() -> NONE);

	private OrgContext() {
	}

	public static long current() {
		return CURRENT.get();
	}

	public static void set(long orgId) {
		CURRENT.set(orgId);
	}

	public static void clear() {
		CURRENT.remove();
	}

	/** Runs {@code work} for {@code orgId}, then puts back whatever was set before. */
	public static <T> T runAs(long orgId, Supplier<T> work) {
		long previous = current();
		set(orgId);
		try {
			return work.get();
		}
		finally {
			set(previous);
		}
	}

	public static <T> T runAsRoot(Supplier<T> work) {
		return runAs(ROOT, work);
	}

}
```

- [ ] **Step 4: Write `OrgTenantResolver` and `TenancyConfig`**

```java
package com.group2.incident.common.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/** Hands Hibernate the org from {@link OrgContext} when a session opens. */
@Component
public class OrgTenantResolver implements CurrentTenantIdentifierResolver<Long> {

	@Override
	public Long resolveCurrentTenantIdentifier() {
		return OrgContext.current();
	}

	// A session keeps the org it opened with; a thread switching org must start a new transaction.
	@Override
	public boolean validateExistingCurrentSessions() {
		return false;
	}

	@Override
	public boolean isRoot(Long tenantId) {
		return tenantId != null && tenantId == OrgContext.ROOT;
	}

}
```

```java
package com.group2.incident.common.tenancy;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TenancyConfig {

	@Bean
	HibernatePropertiesCustomizer orgTenantResolverCustomizer(OrgTenantResolver resolver) {
		return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
	}

}
```

- [ ] **Step 5: Run the test**

Run: `cd backend && ./mvnw -q test -Dtest=OrgContextTest`
Expected: 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/group2/incident/common/tenancy backend/src/test/java/com/group2/incident/common/tenancy/OrgContextTest.java
git commit -m "feat(tenancy): org context and Hibernate tenant resolver (D48)"
```

---

### Task 2: Organizations, memberships, and `org_id` on every org-owned table

The schema and the entities change together: JPA runs with `ddl-auto=validate`, so one cannot land without the other. Usernames stay for now (Task 3 removes them), so the rest of the code keeps compiling.

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__organizations.sql`
- Create: `org/entity/Organization.java`, `org/entity/Membership.java`, `org/repository/OrganizationRepository.java`, `org/repository/MembershipRepository.java`
- Modify: the 13 entities listed in Step 4; `auth/entity/AppUser.java` (add `email`)
- Create: `support/TestOrgExecutionListener.java`; Modify: `support/IntegrationTest.java`
- Test: `common/tenancy/TenantFilteringTest.java`, `db/OrganizationsMigrationTest.java`

**Interfaces:**
- Consumes: `OrgContext` (Task 1).
- Produces: `Organization(String name, String slug, Instant createdAt)`, `Organization.takeIncidentNumber(): long`, `OrganizationRepository.findBySlug(String)`, `OrganizationRepository.existsBySlug(String)`, `OrganizationRepository.findLockedById(Long)`; `Membership(Organization org, AppUser user, Role role, Instant createdAt)`, `Membership.getRole()`, `isEnabled()`, `changeRole(Role)`, `disable()`, `enable()`; `MembershipRepository.findByUserIdAndOrgId(Long, Long)`, `findFirstByUserIdAndEnabledTrue(Long)`, `findAllByOrgIdOrderByIdAsc(Long)`, `countByOrgIdAndRoleAndEnabledTrue(Long, Role)`; `AppUser.getEmail()`; every org-owned entity has `getOrgId()`; test listener constant `TestOrgExecutionListener.TEST_ORG_SLUG = "test-org"`.

- [ ] **Step 1: Write the failing filter test**

```java
package com.group2.incident.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.org.entity.Organization;
import com.group2.incident.org.repository.OrganizationRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import com.group2.incident.support.TestcontainersConfiguration;

/**
 * Proves @TenantId filters queries and findById against PostgreSQL. No test transaction: each
 * TransactionTemplate call opens its own session, so it picks up the org set just before it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TenantFilteringTest {

	private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

	@Autowired private OrganizationRepository orgs;
	@Autowired private AppUserRepository users;
	@Autowired private ServiceRepository services;
	@Autowired private TransactionTemplate tx;
	@Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

	// Unique per run: this test commits its rows.
	private final String suffix = Long.toString(System.nanoTime());
	private Long orgA;
	private Long orgB;
	private Long serviceOfB;

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM service WHERE org_id IN (?, ?)", orgA, orgB);
		jdbc.update("DELETE FROM app_user WHERE email = ?", "tenant-owner-" + suffix + "@test.local");
		jdbc.update("DELETE FROM organization WHERE id IN (?, ?)", orgA, orgB);
		OrgContext.clear();
	}

	@Test
	void anOrgSeesOnlyItsOwnRowsByQueryAndById() {
		tx.execute(status -> {
			orgA = orgs.save(new Organization("Org A", "tenant-a-" + suffix, NOW)).getId();
			orgB = orgs.save(new Organization("Org B", "tenant-b-" + suffix, NOW)).getId();
			return null;
		});
		AppUser owner = tx.execute(status -> users.save(new AppUser("tenant-owner-" + suffix, "tenant-owner-" + suffix + "@test.local", "Owner", "{noop}x",
				com.group2.incident.auth.entity.Role.ADMIN, NOW)));
		OrgContext.runAs(orgA, () -> tx.execute(status -> services.save(new ServiceEntity("Payments", "A", owner, Criticality.HIGH, NOW))));
		serviceOfB = OrgContext.runAs(orgB, () -> tx.execute(status ->
				services.save(new ServiceEntity("Payments", "B", owner, Criticality.HIGH, NOW)).getId()));

		OrgContext.runAs(orgA, () -> tx.execute(status -> {
			assertThat(services.findAll()).extracting(ServiceEntity::getDescription).containsExactly("A");
			assertThat(services.findById(serviceOfB)).isEmpty();
			return null;
		}));
		OrgContext.runAs(OrgContext.NONE, () -> tx.execute(status -> {
			assertThat(services.findAll()).isEmpty();
			return null;
		}));
	}

}
```

(The `AppUser` constructor gains `email` as its second argument in this task; usernames and roles are removed in Task 3.)

- [ ] **Step 2: Run it and see it fail**

Run: `cd backend && ./mvnw -q test -Dtest=TenantFilteringTest`
Expected: compilation failure (`Organization`, the new `AppUser` constructor).

- [ ] **Step 3: Write the migration `V10__organizations.sql`**

If `main` already has a `V10`, use the next free number for this file and for `V11` in Task 3.

```sql
-- Organizations (D48). Anyone signs up and creates an org; each org sees only its own data.
-- organization, app_user, and membership are global. Every other table that holds org data gets
-- org_id, filled and filtered by Hibernate's @TenantId. service_sla_target does not: it is an element
-- collection of service and is only read through its service.

CREATE TABLE organization (
    id                   bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name                 varchar(100) NOT NULL,
    slug                 varchar(60)  NOT NULL,
    next_incident_number bigint       NOT NULL DEFAULT 1,
    created_at           timestamptz  NOT NULL,
    CONSTRAINT organization_slug_key UNIQUE (slug),
    CONSTRAINT organization_slug_check CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

-- Existing rows (local databases, the startup admin) move into one org with id 1.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM app_user) THEN
        INSERT INTO organization (id, name, slug, created_at) VALUES (1, 'Dev org', 'dev-org', now());
    END IF;
END $$;
SELECT setval(pg_get_serial_sequence('organization', 'id'), COALESCE((SELECT max(id) FROM organization), 0) + 1, false);

CREATE TABLE membership (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id     bigint      NOT NULL REFERENCES organization (id),
    user_id    bigint      NOT NULL REFERENCES app_user (id),
    role       varchar(20) NOT NULL,
    enabled    boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    CONSTRAINT membership_org_user_key UNIQUE (org_id, user_id),
    -- One org per person for now (D48); dropping this allows several.
    CONSTRAINT membership_user_key UNIQUE (user_id),
    CONSTRAINT membership_role_check CHECK (role IN ('ADMIN', 'ENGINEER', 'VIEWER'))
);
CREATE INDEX membership_org_idx ON membership (org_id);

INSERT INTO membership (org_id, user_id, role, enabled, created_at)
SELECT 1, id, role, enabled, created_at FROM app_user;

-- Email is the sign-in name. Existing accounts become <username>@settle.local.
ALTER TABLE app_user ADD COLUMN email varchar(254);
UPDATE app_user SET email = lower(username) || '@settle.local';
ALTER TABLE app_user ALTER COLUMN email SET NOT NULL;
CREATE UNIQUE INDEX app_user_email_key ON app_user (lower(email));

-- org_id with a constant default fills existing rows without an UPDATE, so the append-only triggers
-- on audit_entry and timeline_entry never fire. The default is dropped straight after.
ALTER TABLE service              ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE on_call_shift        ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE escalation_policy    ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE escalation_step      ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE escalation_task      ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE escalation_task_step ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE incident             ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE timeline_entry       ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE monitoring_tool      ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE alert                ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE notification         ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE audit_entry          ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);
ALTER TABLE outbox               ADD COLUMN org_id bigint NOT NULL DEFAULT 1 REFERENCES organization (id);

ALTER TABLE service              ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE on_call_shift        ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE escalation_policy    ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE escalation_step      ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE escalation_task      ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE escalation_task_step ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE incident             ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE timeline_entry       ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE monitoring_tool      ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE alert                ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE notification         ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE audit_entry          ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE outbox               ALTER COLUMN org_id DROP DEFAULT;

CREATE INDEX service_org_idx              ON service (org_id);
CREATE INDEX on_call_shift_org_idx        ON on_call_shift (org_id);
CREATE INDEX escalation_policy_org_idx    ON escalation_policy (org_id);
CREATE INDEX escalation_step_org_idx      ON escalation_step (org_id);
CREATE INDEX escalation_task_org_idx      ON escalation_task (org_id);
CREATE INDEX escalation_task_step_org_idx ON escalation_task_step (org_id);
CREATE INDEX incident_org_idx             ON incident (org_id);
CREATE INDEX timeline_entry_org_idx       ON timeline_entry (org_id);
CREATE INDEX monitoring_tool_org_idx      ON monitoring_tool (org_id);
CREATE INDEX alert_org_idx                ON alert (org_id);
CREATE INDEX notification_org_idx         ON notification (org_id);
CREATE INDEX audit_entry_org_idx          ON audit_entry (org_id);
CREATE INDEX outbox_org_idx               ON outbox (org_id);

-- Unique within an org, not across all orgs.
ALTER TABLE service DROP CONSTRAINT service_name_key;
DROP INDEX service_name_lower_key;
CREATE UNIQUE INDEX service_org_name_key ON service (org_id, lower(name));

DROP INDEX monitoring_tool_name_key;
CREATE UNIQUE INDEX monitoring_tool_org_name_key ON monitoring_tool (org_id, lower(name));

ALTER TABLE alert DROP CONSTRAINT alert_source_event_id_key;
ALTER TABLE alert ADD CONSTRAINT alert_org_source_event_id_key UNIQUE (org_id, source, event_id);

-- Each org numbers its incidents from 1. Existing incidents keep their number, which was their id.
ALTER TABLE incident ADD COLUMN number bigint;
UPDATE incident SET number = id;
ALTER TABLE incident ALTER COLUMN number SET NOT NULL;
ALTER TABLE incident ADD CONSTRAINT incident_org_number_key UNIQUE (org_id, number);
UPDATE organization o
SET next_incident_number = COALESCE((SELECT max(number) FROM incident i WHERE i.org_id = o.id), 0) + 1;
```

- [ ] **Step 4: Add `@TenantId` to the 13 org-owned entities**

Add this field to each class below, after its `@Id` field, with the imports `org.hibernate.annotations.TenantId` and `jakarta.persistence.Column`:

```java
	// Filled and filtered by Hibernate from OrgContext (D48); never set by hand.
	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;
```

Classes (each has Lombok `@Getter`, so `getOrgId()` comes for free):
`catalog/entity/ServiceEntity.java`, `catalog/entity/OnCallShift.java`, `incident/entity/EscalationPolicy.java`, `incident/entity/EscalationStep.java`, `incident/entity/EscalationTask.java`, `incident/entity/EscalationTaskStep.java`, `incident/entity/Incident.java`, `incident/entity/TimelineEntry.java`, `alert/entity/MonitoringTool.java`, `alert/entity/Alert.java`, `notification/entity/Notification.java`, `audit/entity/AuditEntry.java`, `incident/entity/Outbox.java`.

In `Incident.java` also add the number and make the reference use it:

```java
	@Column(nullable = false, updatable = false)
	private Long number;

	/** Called once, right after construction, with the org's next number (IncidentService). */
	public void assignNumber(long number) {
		if (this.number != null) {
			throw new IllegalStateException("Incident " + this.number + " already has a number");
		}
		this.number = number;
	}

	public String getReference() {
		return "INC-" + number;
	}
```

- [ ] **Step 5: Add the org entities and repositories**

```java
package com.group2.incident.org.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An org: a company, a team, or one person (D48). Global: not filtered by org. */
@Entity
@Table(name = "organization")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String slug;

	@Column(nullable = false)
	private long nextIncidentNumber = 1;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	public Organization(String name, String slug, Instant createdAt) {
		this.name = name;
		this.slug = slug;
		this.createdAt = createdAt;
	}

	/** Returns this org's next incident number. Call on a row loaded with a write lock. */
	public long takeIncidentNumber() {
		return nextIncidentNumber++;
	}

}
```

```java
package com.group2.incident.org.entity;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A person's role in an org. Global: every query names the org explicitly. */
@Entity
@Table(name = "membership")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Membership {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "org_id")
	private Organization org;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private AppUser user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	public Membership(Organization org, AppUser user, Role role, Instant createdAt) {
		this.org = org;
		this.user = user;
		this.role = role;
		this.createdAt = createdAt;
	}

	public void changeRole(Role newRole) {
		this.role = newRole;
	}

	public void disable() {
		this.enabled = false;
	}

	public void enable() {
		this.enabled = true;
	}

}
```

```java
package com.group2.incident.org.repository;

import com.group2.incident.org.entity.Organization;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

	Optional<Organization> findBySlug(String slug);

	boolean existsBySlug(String slug);

	// Serialises incident numbering within one org (IncidentService).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Organization o where o.id = :id")
	Optional<Organization> findLockedById(@Param("id") Long id);

}
```

```java
package com.group2.incident.org.repository;

import com.group2.incident.auth.entity.Role;
import com.group2.incident.org.entity.Membership;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

	@EntityGraph(attributePaths = { "org", "user" })
	Optional<Membership> findByUserIdAndOrgId(Long userId, Long orgId);

	@EntityGraph(attributePaths = { "org", "user" })
	Optional<Membership> findFirstByUserIdAndEnabledTrue(Long userId);

	@EntityGraph(attributePaths = { "user" })
	List<Membership> findAllByOrgIdOrderByIdAsc(Long orgId);

	long countByOrgIdAndRoleAndEnabledTrue(Long orgId, Role role);

}
```

In `AppUser.java` add the email field and put it into the constructor as the second argument (username, role, and enabled stay until Task 3):

```java
	@Column(nullable = false)
	private String email;

	public AppUser(String username, String email, String displayName, String passwordHash, Role role, Instant createdAt) {
		this.username = username;
		this.email = email;
		this.displayName = displayName;
		this.passwordHash = passwordHash;
		this.role = role;
		this.createdAt = createdAt;
	}
```

Update the two production callers: `UserAdminService.createUser` passes `request.username() + "@settle.local"` for now (Task 5 replaces it with the request's email), and `BootstrapAdmin` passes `username + "@settle.local"` (deleted in Task 3).

- [ ] **Step 6: Give every integration test an org**

Hibernate reads the org when a session opens, and `@IntegrationTest` opens its transaction before `@BeforeEach`. A listener ordered before Spring's transactional listener (order 4000) sets a shared test org first.

```java
package com.group2.incident.support;

import com.group2.incident.common.tenancy.OrgContext;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Puts every test in one committed "Test org" before its transaction starts (Hibernate reads the
 * org when a session opens). Tests that need a second org create it themselves.
 */
public class TestOrgExecutionListener extends AbstractTestExecutionListener {

	public static final String TEST_ORG_SLUG = "test-org";

	@Override
	public int getOrder() {
		return 3500;
	}

	@Override
	public void beforeTestMethod(TestContext testContext) {
		JdbcTemplate jdbc = testContext.getApplicationContext().getBean(JdbcTemplate.class);
		jdbc.update("INSERT INTO organization (name, slug, created_at) VALUES ('Test org', ?, now()) ON CONFLICT (slug) DO NOTHING",
				TEST_ORG_SLUG);
		Long orgId = jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, TEST_ORG_SLUG);
		OrgContext.set(orgId);
	}

	@Override
	public void afterTestMethod(TestContext testContext) {
		OrgContext.clear();
	}

}
```

In `support/IntegrationTest.java`, add the listener:

```java
@TestExecutionListeners(
		listeners = { RateLimitResetTestExecutionListener.class, TestOrgExecutionListener.class },
		mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
```

Then update every `new AppUser(...)` in `backend/src/test` (25 files: `grep -rln "new AppUser(" backend/src/test`) to pass an email as the second argument: `username + "@test.local"` where the username is a variable, or the literal (for example `"admin"` becomes `"admin", "admin@test.local"`).

- [ ] **Step 7: Write the migration test**

```java
package com.group2.incident.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.support.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/** Migrates a separate schema to V9, adds rows, then runs V10 and checks where the rows went. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrganizationsMigrationTest {

	@Autowired private DataSource dataSource;

	@Test
	void existingRowsMoveIntoDevOrg() {
		String schema = "migration_check";
		Flyway.configure().dataSource(dataSource).schemas(schema).target("9").cleanDisabled(false).load().migrate();
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("SET search_path = " + schema);
		jdbc.update("INSERT INTO app_user (username, display_name, password_hash, role, created_at) VALUES ('Admin', 'Admin', 'x', 'ADMIN', now())");
		jdbc.update("INSERT INTO service (name, owner_id, criticality, created_at, updated_at) SELECT 'Payments', id, 'HIGH', now(), now() FROM app_user");

		Flyway.configure().dataSource(dataSource).schemas(schema).target("10").load().migrate();

		jdbc.execute("SET search_path = " + schema);
		assertThat(jdbc.queryForObject("SELECT slug FROM organization WHERE id = 1", String.class)).isEqualTo("dev-org");
		assertThat(jdbc.queryForObject("SELECT email FROM app_user", String.class)).isEqualTo("admin@settle.local");
		assertThat(jdbc.queryForObject("SELECT role FROM membership", String.class)).isEqualTo("ADMIN");
		assertThat(jdbc.queryForObject("SELECT org_id FROM service", Long.class)).isEqualTo(1L);
		Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load().clean();
	}

	@Test
	void anEmptyDatabaseGetsNoOrg() {
		String schema = "migration_empty";
		Flyway.configure().dataSource(dataSource).schemas(schema).target("10").load().migrate();
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".organization", Long.class)).isZero();
		Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load().clean();
	}

}
```

If a column list in the `INSERT INTO service` differs from V2's (check `backend/src/main/resources/db/migration/V2__catalog.sql`), match V2's required columns.

- [ ] **Step 8: Run the whole backend suite**

Run: `cd backend && ./mvnw verify`
Expected: `TenantFilteringTest`, `OrganizationsMigrationTest`, `ArchitectureTest`, and every existing test pass. A failure that says an `org_id` is null means an entity in Step 4 is missing its `@TenantId`; a failure on `incident.number` means `IncidentService` does not call `assignNumber` yet: in `IncidentService` and `AlertService`, right after `new Incident(...)`, add `incident.assignNumber(orgs.findLockedById(OrgContext.current()).orElseThrow().takeIncidentNumber());` with `OrganizationRepository orgs` injected (Task 6 adds its tests). Test classes that build `Incident` objects without saving them (`IncidentTest`, `EscalationServiceTest`) call `incident.assignNumber(1L)` after construction.

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "feat(tenancy): organizations, memberships, and org_id on every org-owned table (D48)"
```

---

### Task 3: Sign in by email; the token carries the org

**Files:**
- Create: `common/tenancy/OrgContextFilter.java`, `backend/src/main/resources/db/migration/V11__drop_usernames.sql`
- Modify: `auth/entity/AppUser.java`, `auth/dto/LoginRequest.java`, `auth/dto/LoginResponse.java`, `auth/dto/UserResponse.java`, `auth/service/AuthService.java`, `auth/service/AppUserDetailsService.java`, `auth/repository/AppUserRepository.java`, `auth/security/JwtUserConverter.java`, `auth/security/SecurityConfig.java`, `backend/src/main/resources/db/devdata/R__dev_seed.sql`, `backend/src/main/resources/application.properties`
- Delete: `auth/service/BootstrapAdmin.java`, test `auth/service/BootstrapAdminTest.java`
- Create: `org/dto/OrgResponse.java`, test support `support/TestAccounts.java`
- Test: `auth/controller/AuthControllerTest.java` (update), every test that logs in

**Interfaces:**
- Consumes: `Membership`, `MembershipRepository`, `OrgContext`.
- Produces: `AppUser(String email, String displayName, String passwordHash, Instant createdAt)`; `AppUserRepository.findByEmail(String)`; JWT claims `org` (Long) and `role` (String); `JwtUserConverter.ORG = "org"`; `LoginRequest(String email, String password)`; `UserResponse(Long id, String email, String displayName, Role role)`; `OrgResponse(Long id, String name, String slug)`; `LoginResponse(String token, Instant expiresAt, UserResponse user, OrgResponse org)`; `AuthService.issueToken(Membership): LoginResponse`; test helper `TestAccounts.create(String email, Role role): AppUser` and `TestAccounts.token(String email): String`.

- [ ] **Step 1: Write the failing tests**

In `auth/controller/AuthControllerTest.java`, replace user creation with `TestAccounts` (below) and add:

```java
	@Test
	void loginTakesAnEmailAndTheTokenCarriesTheOrgAndRole() throws Exception {
		accounts.create("ana@test.local", Role.ENGINEER);
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"ANA@test.local\",\"password\":\"DevPassw0rd!\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.email").value("ana@test.local"))
				.andExpect(jsonPath("$.user.role").value("ENGINEER"))
				.andExpect(jsonPath("$.org.slug").value("test-org"))
				.andReturn().getResponse().getContentAsString();
		Jwt jwt = jwtDecoder.decode(JsonPath.read(body, "$.token"));
		assertThat(((Number) jwt.getClaim("org")).longValue()).isEqualTo(OrgContext.current());
		assertThat((String) jwt.getClaim("role")).isEqualTo("ENGINEER");
	}

	@Test
	void aDisabledMembershipGetsTheSame401AsAWrongPassword() throws Exception {
		AppUser off = accounts.create("off@test.local", Role.ENGINEER);
		memberships.findByUserIdAndOrgId(off.getId(), OrgContext.current()).orElseThrow().disable();
		memberships.flush();
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"off@test.local\",\"password\":\"DevPassw0rd!\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid email or password"));
	}

	@Test
	void meReturnsTheOrg() throws Exception {
		accounts.create("me@test.local", Role.VIEWER);
		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accounts.token("me@test.local")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.org.slug").value("test-org"));
	}
```

(Autowire `TestAccounts accounts`, `MembershipRepository memberships`, and `JwtDecoder jwtDecoder`. Step 4 renames the 401 message.)

`support/TestAccounts.java`:

```java
package com.group2.incident.support;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.MembershipRepository;
import com.group2.incident.org.repository.OrganizationRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Accounts in the current test org, with the dev password, and their login tokens. */
@Component
public class TestAccounts {

	public static final String PASSWORD = "DevPassw0rd!";

	private final AppUserRepository users;
	private final MembershipRepository memberships;
	private final OrganizationRepository orgs;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;
	private final MockMvc mvc;

	public TestAccounts(AppUserRepository users, MembershipRepository memberships, OrganizationRepository orgs,
			PasswordEncoder passwordEncoder, Clock clock, MockMvc mvc) {
		this.users = users;
		this.memberships = memberships;
		this.orgs = orgs;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
		this.mvc = mvc;
	}

	public AppUser create(String email, Role role) {
		AppUser user = users.saveAndFlush(new AppUser(email, displayName(email), passwordEncoder.encode(PASSWORD), clock.instant()));
		memberships.saveAndFlush(new Membership(orgs.getReferenceById(OrgContext.current()), user, role, clock.instant()));
		return user;
	}

	public String token(String email) throws Exception {
		String body = mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.token");
	}

	private static String displayName(String email) {
		return email.substring(0, email.indexOf('@'));
	}

}
```

Register it for integration tests by adding `TestAccounts.class` to the `@Import` in `IntegrationTest.java`: `@Import({ TestcontainersConfiguration.class, TestAccounts.class })`.

- [ ] **Step 2: Run them and see them fail**

Run: `cd backend && ./mvnw -q test -Dtest=AuthControllerTest`
Expected: compilation errors (new `AppUser` constructor, `TestAccounts`), then failures.

- [ ] **Step 3: Drop usernames and roles from `app_user`**

`V11__drop_usernames.sql`:

```sql
-- Email is the sign-in name, and the role and enabled flag live on the membership (D48).
DROP INDEX app_user_username_key;
ALTER TABLE app_user DROP COLUMN username;
ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
ALTER TABLE app_user DROP COLUMN role;
ALTER TABLE app_user DROP COLUMN enabled;
-- Google-only accounts (part 2) have no password.
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;
```

`AppUser.java` becomes:

```java
@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String displayName;

	private String passwordHash;

	// Carried in every token as "tv"; a token with an older value is refused (D21).
	@Column(nullable = false)
	private int tokenVersion;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	public AppUser(String email, String displayName, String passwordHash, Instant createdAt) {
		this.email = email.trim().toLowerCase(java.util.Locale.ROOT);
		this.displayName = displayName;
		this.passwordHash = passwordHash;
		this.createdAt = createdAt;
	}

	/** Every token issued so far stops working. Used by logout and role changes. */
	public void revokeTokens() {
		tokenVersion++;
	}

}
```

`AppUserRepository.java`: replace `findByUsername` with

```java
	// lower() on both sides, so the case-insensitive unique index from V10 applies.
	@Query("select u from AppUser u where lower(u.email) = lower(:email)")
	Optional<AppUser> findByEmail(@Param("email") String email);
```

- [ ] **Step 4: Tokens carry the org; the role comes from the membership**

`AppUserDetailsService.loadUserByUsername(String email)`:

```java
	@Override
	public UserDetails loadUserByUsername(String email) {
		AppUser user = users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
		Membership membership = memberships.findFirstByUserIdAndEnabledTrue(user.getId())
				.orElseThrow(() -> new UsernameNotFoundException("No active membership"));
		return User.withUsername(user.getEmail())
				.password(user.getPasswordHash() == null ? "{noop}" + java.util.UUID.randomUUID() : user.getPasswordHash())
				.roles(membership.getRole().name())
				.build();
	}
```

`AuthService` (the `login` and `me` methods; `logout` is unchanged):

```java
	public LoginResponse login(LoginRequest request) {
		Authentication authentication = authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
		AppUser user = users.findByEmail(authentication.getName()).orElseThrow();
		Membership membership = memberships.findFirstByUserIdAndEnabledTrue(user.getId()).orElseThrow();
		return issueToken(membership);
	}

	/** Used by login and sign-up. */
	public LoginResponse issueToken(Membership membership) {
		AppUser user = membership.getUser();
		Instant now = clock.instant();
		Instant expiresAt = now.plus(TOKEN_LIFETIME);
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(user.getId().toString())
				.issuedAt(now)
				.expiresAt(expiresAt)
				.claim(JwtUserConverter.TOKEN_VERSION, user.getTokenVersion())
				.claim(JwtUserConverter.ORG, membership.getOrg().getId())
				.claim(JwtUserConverter.ROLE, membership.getRole().name())
				.build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new LoginResponse(token, expiresAt, UserResponse.from(membership), OrgResponse.from(membership.getOrg()));
	}

	@Transactional(readOnly = true)
	public MeResponse me(long userId, long orgId) {
		Membership membership = memberships.findByUserIdAndOrgId(userId, orgId)
				.orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
		return new MeResponse(UserResponse.from(membership), OrgResponse.from(membership.getOrg()));
	}
```

Inject `MembershipRepository memberships`. New and changed DTOs:

```java
public record LoginRequest(
		@NotBlank(message = "Email is required") @Email(message = "Enter a valid email")
		@Size(max = 254, message = "Email is at most 254 characters") String email,
		@NotBlank(message = "Password is required") @Size(max = 72, message = "Password is at most 72 characters") String password) {
}

public record UserResponse(Long id, String email, String displayName, Role role) {

	public static UserResponse from(Membership membership) {
		AppUser user = membership.getUser();
		return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), membership.getRole());
	}

}

public record LoginResponse(String token, Instant expiresAt, UserResponse user, OrgResponse org) {
}

public record MeResponse(UserResponse user, OrgResponse org) {
}
```

`org/dto/OrgResponse.java`:

```java
package com.group2.incident.org.dto;

import com.group2.incident.org.entity.Organization;

public record OrgResponse(Long id, String name, String slug) {

	public static OrgResponse from(Organization org) {
		return new OrgResponse(org.getId(), org.getName(), org.getSlug());
	}

}
```

`AuthController`: rate-limit by email (`rateLimitService.checkLogin(servletRequest.getRemoteAddr(), request.email())`), and `me` returns `MeResponse` from `auth.me(userId(jwt), orgId(jwt))` with `private static long orgId(Jwt jwt) { return ((Number) jwt.getClaim(JwtUserConverter.ORG)).longValue(); }`.

`JwtUserConverter`:

```java
	public static final String TOKEN_VERSION = "tv";
	public static final String ORG = "org";
	public static final String ROLE = "role";

	private final AppUserRepository users;
	private final MembershipRepository memberships;

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		if (!(jwt.getClaim(ORG) instanceof Number org)) {
			throw new InvalidBearerTokenException("Token is no longer valid");
		}
		Membership membership = memberships.findByUserIdAndOrgId(Long.valueOf(jwt.getSubject()), org.longValue())
				.filter(Membership::isEnabled)
				// Numbers in a JWT decode as Long.
				.filter(m -> jwt.getClaim(TOKEN_VERSION) instanceof Number version && version.intValue() == m.getUser().getTokenVersion())
				.orElseThrow(() -> new InvalidBearerTokenException("Token is no longer valid"));
		// The role comes from the membership, not the token, so a role change applies at once.
		return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + membership.getRole().name())));
	}
```

(`users` is no longer used in the converter; remove the field.)

In `common/error/ApiExceptionHandler.java`, `failedLogin` now says `"Invalid email or password"` instead of `"Invalid username or password"`; update the existing tests that assert the old text.

- [ ] **Step 5: Set the org for each request**

```java
package com.group2.incident.common.tenancy;

import com.group2.incident.auth.security.JwtUserConverter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after the token is checked: the request works for the org in the token, then the old value returns. */
public class OrgContextFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken token)
				|| !(token.getToken().getClaim(JwtUserConverter.ORG) instanceof Number org)) {
			chain.doFilter(request, response);
			return;
		}
		long previous = OrgContext.current();
		OrgContext.set(org.longValue());
		try {
			chain.doFilter(request, response);
		}
		finally {
			OrgContext.set(previous);
		}
	}

}
```

In `SecurityConfig.securityFilterChain`, add after `.oauth2ResourceServer(...)`:

```java
		http.addFilterAfter(new OrgContextFilter(), BearerTokenAuthenticationFilter.class);
```

(import `org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter`). Restoring the previous value matters in tests, where MockMvc runs on the test's own thread.

- [ ] **Step 6: Remove the bootstrap admin; reseed dev data**

Delete `BootstrapAdmin.java` and `BootstrapAdminTest.java`, and the two `app.bootstrap-admin.*` lines from `application.properties`. Replace `R__dev_seed.sql` with:

```sql
-- Development data only. Flyway loads this file only when FLYWAY_LOCATIONS includes
-- classpath:db/devdata (D27); production and the default test run never see it.
-- One org, "Dev org", with admin@settle.local, engineer@settle.local, and viewer@settle.local.
-- Every password is DevPassw0rd!. Repeatable and idempotent.

INSERT INTO organization (name, slug, created_at)
VALUES ('Dev org', 'dev-org', now())
ON CONFLICT (slug) DO NOTHING;

INSERT INTO app_user (email, display_name, password_hash, created_at)
VALUES ('admin@settle.local',    'Dev Admin',    '{bcrypt}$2a$10$VIfFAxI6UFgBeWMdFEvAp.16koHo8wb8LRuqky0e47iWLefM5Huwm', now()),
       ('engineer@settle.local', 'Dev Engineer', '{bcrypt}$2a$10$VIfFAxI6UFgBeWMdFEvAp.16koHo8wb8LRuqky0e47iWLefM5Huwm', now()),
       ('viewer@settle.local',   'Dev Viewer',   '{bcrypt}$2a$10$VIfFAxI6UFgBeWMdFEvAp.16koHo8wb8LRuqky0e47iWLefM5Huwm', now())
ON CONFLICT ((lower(email))) DO NOTHING;

INSERT INTO membership (org_id, user_id, role, created_at)
SELECT o.id, u.id, r.role, now()
FROM organization o
JOIN (VALUES ('admin@settle.local', 'ADMIN'), ('engineer@settle.local', 'ENGINEER'), ('viewer@settle.local', 'VIEWER'))
    AS r (email, role) ON true
JOIN app_user u ON lower(u.email) = r.email
WHERE o.slug = 'dev-org'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO service (org_id, name, description, owner_id, criticality, created_at, updated_at)
SELECT o.id, 'Payments', 'Card and wallet payments', u.id, 'CRITICAL', now(), now()
FROM organization o JOIN app_user u ON lower(u.email) = 'admin@settle.local'
WHERE o.slug = 'dev-org'
ON CONFLICT (org_id, lower(name)) DO NOTHING;

INSERT INTO service_sla_target (service_id, severity, response_minutes, resolution_minutes)
SELECT s.id, t.severity, t.response_minutes, t.resolution_minutes
FROM service s
JOIN organization o ON o.id = s.org_id AND o.slug = 'dev-org'
CROSS JOIN (VALUES ('SEV1', 15, 240),
                   ('SEV2', 60, 480),
                   ('SEV3', 240, 2880),
                   ('SEV4', 1440, 10080)) AS t (severity, response_minutes, resolution_minutes)
WHERE s.name = 'Payments'
ON CONFLICT (service_id, severity) DO NOTHING;
```

Keep any further statements the old seed has after the SLA targets, rewritten the same way (`org_id` from the `dev-org` row, users by `email`).

- [ ] **Step 7: Move the test suite to emails**

Every test that creates users or logs in uses `TestAccounts`:
- `users.saveAndFlush(new AppUser(name, ..., role, ...))` becomes `accounts.create(name + "@test.local", role)`.
- A login helper that posts `{"username": …}` becomes `accounts.token(name + "@test.local")`.
- Assertions on `$.user.username` or `$.username` become `$.user.email` or `$.email` with the `@test.local` address.
- `user.getRole()` in tests becomes the membership's role: `memberships.findByUserIdAndOrgId(user.getId(), OrgContext.current()).orElseThrow().getRole()`.

Files: `grep -rln "new AppUser(\|\"username\"\|getUsername\|getRole()" backend/src/test`.

- [ ] **Step 8: Run the suite**

Run: `cd backend && ./mvnw verify`
Expected: all tests pass, including the three new ones in `AuthControllerTest`.

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "feat(auth): sign in by email; the token carries the org and the role comes from the membership (D48)"
```

---

### Task 4: Sign up and create an org

**Files:**
- Create: `org/service/Slugs.java`, `org/service/SignupService.java`, `org/controller/SignupController.java`, `org/dto/SignupRequest.java`
- Modify: `auth/security/SecurityConfig.java` (permit sign-up), `audit/entity/AuditAction.java` (`ORG_CREATED`), `audit/entity/AuditItemType.java` (`ORG`)
- Test: `org/service/SlugsTest.java`, `org/SignupControllerTest.java`, a `SecurityMatrixTest` row

**Interfaces:**
- Consumes: `AuthService.issueToken(Membership)`, `OrganizationRepository`, `MembershipRepository`, `AuditService.record(...)`, `RateLimitService.checkLogin(String, String)`.
- Produces: `POST /api/auth/signup` → 201 `LoginResponse`; `Slugs.from(String name): String`.

- [ ] **Step 1: Write the failing tests**

```java
package com.group2.incident.org.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlugsTest {

	@Test
	void lowerCasesAndJoinsWordsWithHyphens() {
		assertThat(Slugs.from("DA-IICT Group 2")).isEqualTo("da-iict-group-2");
	}

	@Test
	void dropsAccentsAndOtherCharactersAndTrimsHyphens() {
		assertThat(Slugs.from("  Café & Co.!  ")).isEqualTo("cafe-co");
	}

	@Test
	void anEmptyResultBecomesOrg() {
		assertThat(Slugs.from("!!!")).isEqualTo("org");
	}

	@Test
	void capsAtFiftyCharacters() {
		assertThat(Slugs.from("a".repeat(80))).hasSize(50);
	}

}
```

```java
package com.group2.incident.org;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SignupControllerTest {

	@Autowired private MockMvc mvc;

	private static String body(String email, String org) {
		return """
				{"email":"%s","password":"DevPassw0rd!","displayName":"Ana","orgName":"%s"}""".formatted(email, org);
	}

	@Test
	void signUpCreatesTheOrgWithYouAsAdminAndSignsYouIn() throws Exception {
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("ana@acme.test", "Acme Corp")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.token").isString())
				.andExpect(jsonPath("$.user.role").value("ADMIN"))
				.andExpect(jsonPath("$.org.name").value("Acme Corp"))
				.andExpect(jsonPath("$.org.slug").value("acme-corp"));
	}

	@Test
	void aTakenEmailGives409() throws Exception {
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("dup@acme.test", "One")))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("DUP@acme.test", "Two")))
				.andExpect(status().isConflict());
	}

	@Test
	void twoOrgsWithOneNameGetDifferentSlugs() throws Exception {
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("a@same.test", "Same")))
				.andExpect(jsonPath("$.org.slug").value("same"));
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("b@same.test", "Same")))
				.andExpect(jsonPath("$.org.slug").value("same-2"));
	}

	@Test
	void aMissingOrgNameIsAFieldError() throws Exception {
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("x@acme.test", "")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.orgName").isString());
	}

	@Test
	void theSixthSignUpInAMinuteFromOneAddressGets429() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("rate" + i + "@acme.test", "Rate " + i)))
					.andExpect(status().isCreated());
		}
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("rate5@acme.test", "Rate 5")))
				.andExpect(status().isTooManyRequests());
	}

}
```

Add a row to `SecurityMatrixTest` for `POST /api/auth/signup` with the expected status `400` for every role (the matrix sends an empty body), and anonymous `400`, so the endpoint counts as reachable without login.

- [ ] **Step 2: Run them and see them fail**

Run: `cd backend && ./mvnw -q test -Dtest='SlugsTest,SignupControllerTest'`
Expected: compilation failure (`Slugs`), then 401 on `/api/auth/signup`.

- [ ] **Step 3: Implement**

```java
package com.group2.incident.org.service;

import java.text.Normalizer;
import java.util.Locale;

/** Org names to URL slugs: lower-case letters, digits, and single hyphens, at most 50 characters. */
public final class Slugs {

	private static final int MAX = 50;

	private Slugs() {
	}

	public static String from(String name) {
		String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
		if (slug.length() > MAX) {
			slug = slug.substring(0, MAX).replaceAll("-+$", "");
		}
		return slug.isEmpty() ? "org" : slug;
	}

}
```

```java
package com.group2.incident.org.dto;

import com.group2.incident.common.validation.MaxBytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
		@NotBlank(message = "Email is required") @Email(message = "Enter a valid email")
		@Size(max = 254, message = "Email is at most 254 characters") String email,
		@NotBlank(message = "Password is required")
		@Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
		@MaxBytes(value = 72, message = "Password must be at most 72 bytes; letters such as é count as two") String password,
		@NotBlank(message = "Your name is required") @Size(max = 100, message = "Your name is at most 100 characters") String displayName,
		@NotBlank(message = "Org name is required") @Size(max = 100, message = "Org name is at most 100 characters") String orgName) {
}
```

```java
package com.group2.incident.org.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.dto.LoginResponse;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.auth.service.AuthService;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.org.dto.SignupRequest;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.entity.Organization;
import com.group2.incident.org.repository.MembershipRepository;
import com.group2.incident.org.repository.OrganizationRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the person, the org, and an Admin membership in one transaction, then records the new org
 * in its own audit log. The audit entry is a second transaction because Hibernate fixes a session's
 * org when it opens, before the org exists.
 */
@Service
@RequiredArgsConstructor
public class SignupService {

	private final AppUserRepository users;
	private final OrganizationRepository orgs;
	private final MembershipRepository memberships;
	private final PasswordEncoder passwordEncoder;
	private final AuditService auditService;
	private final AuthService authService;
	private final TransactionTemplate tx;
	private final Clock clock;

	public LoginResponse signup(SignupRequest request) {
		Membership membership = tx.execute(status -> create(request));
		OrgContext.runAs(membership.getOrg().getId(), () -> tx.execute(status -> {
			auditService.record(membership.getUser(), AuditAction.ORG_CREATED, AuditItemType.ORG, membership.getOrg().getId(),
					"name: " + membership.getOrg().getName() + ", slug: " + membership.getOrg().getSlug());
			return null;
		}));
		return authService.issueToken(membership);
	}

	private Membership create(SignupRequest request) {
		if (users.findByEmail(request.email()).isPresent()) {
			throw new ConflictException("An account with this email already exists");
		}
		Instant now = clock.instant();
		AppUser user = users.save(new AppUser(request.email(), request.displayName().trim(), passwordEncoder.encode(request.password()), now));
		Organization org = orgs.save(new Organization(request.orgName().trim(), freeSlug(request.orgName()), now));
		return memberships.save(new Membership(org, user, Role.ADMIN, now));
	}

	private String freeSlug(String name) {
		String base = Slugs.from(name);
		String slug = base;
		for (int n = 2; orgs.existsBySlug(slug); n++) {
			slug = base + "-" + n;
		}
		return slug;
	}

}
```

`SignupService.create` must load nothing org-scoped: it runs while `OrgContext` is `NONE`. `auditService.record(...)` must accept an `AppUser` that is detached from the second transaction; if it calls `getReferenceById`, pass `membership.getUser()` unchanged (`AuditEntry` only stores the id).

```java
package com.group2.incident.org.controller;

import com.group2.incident.auth.dto.LoginResponse;
import com.group2.incident.common.ratelimit.RateLimitService;
import com.group2.incident.org.dto.SignupRequest;
import com.group2.incident.org.service.SignupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Auth", description = "Log in, see who you are, log out")
@RequiredArgsConstructor
class SignupController {

	// One bucket per address for every sign-up, separate from login attempts (D42).
	private static final String SIGNUP_BUCKET = "sign-up";

	private final SignupService signupService;
	private final RateLimitService rateLimitService;

	@PostMapping("/api/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create an account and a new org, with you as its Admin")
	@SecurityRequirements
	LoginResponse signup(@Valid @RequestBody SignupRequest request, HttpServletRequest servletRequest) {
		rateLimitService.checkLogin(servletRequest.getRemoteAddr(), SIGNUP_BUCKET);
		return signupService.signup(request);
	}

}
```

In `SecurityConfig`, next to the login matcher: `.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/signup").permitAll()`. Add `ORG_CREATED` to `AuditAction` and `ORG` to `AuditItemType`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='SlugsTest,SignupControllerTest,SecurityMatrixTest'`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(org): sign up and create an org (D48)"
```

---

### Task 5: Members: Admins add people to their org

**Files:**
- Modify: `auth/dto/CreateUserRequest.java`, `auth/service/UserAdminService.java`, `auth/controller/UserAdminController.java`
- Test: `auth/service/UserAdminServiceTest.java`, `auth/controller/UserAdminControllerTest.java`

**Interfaces:**
- Consumes: `MembershipRepository`, `OrgContext.current()`.
- Produces: `CreateUserRequest(String email, String displayName, String password, Role role)`; `UserAdminService.listUsers(): List<UserResponse>` (this org's members), `createUser(...)`, `changeRole(...)`; 409 "An org needs at least one Admin".

- [ ] **Step 1: Write the failing tests** (in `UserAdminControllerTest`, with `TestAccounts`)

```java
	@Test
	void listShowsOnlyThisOrgsMembers() throws Exception {
		accounts.create("boss@test.local", Role.ADMIN);
		accounts.create("eng@test.local", Role.ENGINEER);
		mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + accounts.token("boss@test.local")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].email").value(org.hamcrest.Matchers.containsInAnyOrder("boss@test.local", "eng@test.local")));
	}

	@Test
	void anAdminAddsAMemberByEmail() throws Exception {
		accounts.create("boss2@test.local", Role.ADMIN);
		mvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + accounts.token("boss2@test.local"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"new@test.local\",\"displayName\":\"New\",\"password\":\"DevPassw0rd!\",\"role\":\"VIEWER\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("new@test.local"))
				.andExpect(jsonPath("$.role").value("VIEWER"));
	}

	@Test
	void theLastAdminCannotBeDemoted() throws Exception {
		AppUser boss = accounts.create("only@test.local", Role.ADMIN);
		mvc.perform(put("/api/users/{id}/role", boss.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + accounts.token("only@test.local"))
						.contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ENGINEER\"}"))
				.andExpect(status().isConflict());
	}
```

- [ ] **Step 2: Run them and see them fail**

Run: `cd backend && ./mvnw -q test -Dtest=UserAdminControllerTest`
Expected: failures (the request still has `username`; no last-Admin rule).

- [ ] **Step 3: Implement**

`CreateUserRequest`: replace `username` with

```java
		@NotBlank(message = "Email is required") @Email(message = "Enter a valid email")
		@Size(max = 254, message = "Email is at most 254 characters")
		String email,
```

`UserAdminService`:

```java
	@Transactional(readOnly = true)
	public List<UserResponse> listUsers() {
		return memberships.findAllByOrgIdOrderByIdAsc(OrgContext.current()).stream().map(UserResponse::from).toList();
	}

	@Transactional
	public UserResponse createUser(CreateUserRequest request, long adminUserId) {
		if (users.findByEmail(request.email()).isPresent()) {
			throw new ConflictException("An account with this email already exists");
		}
		Membership admin = member(adminUserId);
		AppUser user = users.save(new AppUser(request.email(), request.displayName(), passwordEncoder.encode(request.password()), clock.instant()));
		Membership membership = memberships.save(new Membership(admin.getOrg(), user, request.role(), clock.instant()));
		auditService.record(admin.getUser(), AuditAction.USER_CREATED, AuditItemType.USER, user.getId(),
				"email: " + user.getEmail() + ", role: " + membership.getRole());
		return UserResponse.from(membership);
	}

	@Transactional
	public UserResponse changeRole(long targetUserId, ChangeRoleRequest request, long adminUserId) {
		Membership target = member(targetUserId);
		Membership admin = member(adminUserId);
		Role oldRole = target.getRole();
		if (oldRole == Role.ADMIN && request.role() != Role.ADMIN
				&& memberships.countByOrgIdAndRoleAndEnabledTrue(OrgContext.current(), Role.ADMIN) <= 1) {
			throw new ConflictException("An org needs at least one Admin");
		}
		target.changeRole(request.role());
		target.getUser().revokeTokens();
		auditService.record(admin.getUser(), AuditAction.USER_ROLE_CHANGED, AuditItemType.USER, target.getUser().getId(),
				"role: " + oldRole + " → " + request.role());
		return UserResponse.from(target);
	}

	private Membership member(long userId) {
		return memberships.findByUserIdAndOrgId(userId, OrgContext.current())
				.orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
	}
```

Inject `MembershipRepository memberships`; remove the `log.info` lines that used `getUsername()` (keep logging with emails if you want them).

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='UserAdminControllerTest,UserAdminServiceTest'`
Expected: all pass. `UserAdminServiceTest` (mocked) needs the new constructor argument and its mocks moved from `users.findById` to `memberships.findByUserIdAndOrgId`.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(org): members are per org; the last Admin stays an Admin (D48)"
```

---

### Task 6: Each org numbers its incidents from INC-1

**Files:**
- Modify: `incident/service/IncidentService.java`, `alert/service/AlertService.java` (if Task 2 Step 8 has not already)
- Test: `incident/controller/IncidentControllerTest.java`

**Interfaces:**
- Consumes: `OrganizationRepository.findLockedById`, `Organization.takeIncidentNumber`, `Incident.assignNumber`.

- [ ] **Step 1: Write the failing test**

```java
	@Test
	void eachOrgStartsAtInc1() throws Exception {
		// The test org already numbers from 1; a second org also starts at 1.
		String first = declare(adminToken);
		Long otherOrg = jdbc.queryForObject(
				"INSERT INTO organization (name, slug, created_at) VALUES ('Other', 'other-numbering', now()) RETURNING id", Long.class);
		AppUser otherAdmin = OrgContext.runAs(otherOrg, () -> accounts.create("other-admin@test.local", Role.ADMIN));
		ServiceEntity otherService = OrgContext.runAs(otherOrg, () -> /* create a service as in setUp, inside otherOrg */ createService("Other payments", otherAdmin));
		String second = OrgContext.runAs(otherOrg, () -> declareFor(otherService, accounts.token("other-admin@test.local")));
		assertThat(first).isEqualTo("INC-1");
		assertThat(second).isEqualTo("INC-1");
	}
```

This test opens sessions in two orgs, so annotate it `@Transactional(propagation = Propagation.NOT_SUPPORTED)` and delete what it saved at the end (as `IncidentCreationRollbackTest` does). `declare`, `declareFor`, and `createService` are small private helpers in the test class that post `POST /api/incidents` and return `$.reference`, and save a `ServiceEntity` with SLA targets.

- [ ] **Step 2: Run it and see it fail** (if Task 2 Step 8 already added numbering, it passes: go to Step 4)

Run: `cd backend && ./mvnw -q test -Dtest=IncidentControllerTest#eachOrgStartsAtInc1`

- [ ] **Step 3: Implement**

In `IncidentService` and `AlertService`, inject `OrganizationRepository orgs` and, right after `new Incident(...)`:

```java
		incident.assignNumber(orgs.findLockedById(OrgContext.current())
				.orElseThrow(() -> new IllegalStateException("No org for this request"))
				.takeIncidentNumber());
```

The write lock on the org row makes two incidents declared at once in one org take different numbers.

- [ ] **Step 4: Run the incident and alert tests**

Run: `cd backend && ./mvnw -q test -Dtest='IncidentControllerTest,AlertGroupingTest,IncidentCreationRollbackTest'`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(incident): each org numbers its incidents from INC-1 (D48)"
```

---

### Task 7: The alert webhook and the workers find their org

**Files:**
- Modify: `alert/security/ToolTokenInterceptor.java`, `incident/repository/EscalationTaskRepository.java`, `incident/service/EscalationWorker.java`, `incident/service/OutboxRelay.java`
- Create: `incident/repository/OverdueTask.java`
- Test: `alert/controller/AlertWebhookOrgTest.java`, `incident/service/EscalationWorkerOrgTest.java`, `incident/service/OutboxRelayTest.java` (one new case)

**Interfaces:**
- Produces: `record OverdueTask(Long id, Long orgId)`; `EscalationTaskRepository.findOverdueTasks(EscalationTaskState, Instant): List<OverdueTask>`; outbox messages carry header `orgId`.

- [ ] **Step 1: Write the failing tests**

`AlertWebhookOrgTest` (`@IntegrationTest`, `@Transactional(propagation = NOT_SUPPORTED)`, cleans up after itself): create a second org with its own service and tool (`OrgContext.runAs(otherOrg, () -> toolService.registerTool(...))`), post an alert with that tool's token, and assert the alert and its incident were saved in the second org (`OrgContext.runAs(otherOrg, () -> alerts.findAll())` has one row; the test org's `alerts.findAll()` has none).

`EscalationWorkerOrgTest` (same setup style): an overdue escalation task in each of two orgs; `escalationWorker.runOnce(now)` returns 2, and each org's timeline has its own escalation entry.

`OutboxRelayTest`: a new case asserts the received message has the header `orgId` equal to the event's org.

- [ ] **Step 2: Run them and see them fail**

Run: `cd backend && ./mvnw -q test -Dtest='AlertWebhookOrgTest,EscalationWorkerOrgTest,OutboxRelayTest'`
Expected: the webhook gets 401 (the tool lookup runs as `NONE`), the worker finds no tasks, and the header is missing.

- [ ] **Step 3: Implement**

`ToolService.validateToken` is `@Transactional`, and Hibernate fixes a session's org when the transaction opens, so the root lookup wraps the whole call in the interceptor; `ToolService` itself is unchanged. `ToolTokenInterceptor` then sets the tool's org for the rest of the request, and restores it afterwards:

```java
	private static final String PREVIOUS_ORG = ToolTokenInterceptor.class.getName() + ".previousOrg";

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// The token is checked before any org is known, so this one lookup sees every org.
		MonitoringTool tool = OrgContext.runAsRoot(() -> alertIntakeService.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION)));
		rateLimitService.checkWebhook(tool.getId());
		request.setAttribute(PREVIOUS_ORG, OrgContext.current());
		OrgContext.set(tool.getOrgId());
		request.setAttribute(TOOL_ATTRIBUTE, tool);
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		if (request.getAttribute(PREVIOUS_ORG) instanceof Long previous) {
			OrgContext.set(previous);
		}
	}
```

`OverdueTask.java`:

```java
package com.group2.incident.incident.repository;

/** An overdue escalation task and the org it belongs to, read across all orgs by the worker. */
public record OverdueTask(Long id, Long orgId) {
}
```

`EscalationTaskRepository`: replace `findOverdueTaskIds` with the same query returning both fields (keep its existing `WHERE` clause):

```java
	@Query("SELECT new com.group2.incident.incident.repository.OverdueTask(t.id, t.orgId) FROM EscalationTask t "
			+ "WHERE t.state = :state AND t.nextDueAt <= :now ORDER BY t.nextDueAt ASC, t.id ASC")
	List<OverdueTask> findOverdueTasks(@Param("state") EscalationTaskState state, @Param("now") Instant now);
```

Copy any further condition from the current `findOverdueTaskIds` query (`backend/src/main/java/com/group2/incident/incident/repository/EscalationTaskRepository.java:22`) into this one.

`EscalationWorker.runOnce`:

```java
	public int runOnce(Instant now) {
		int processed = 0;
		List<OverdueTask> overdue = OrgContext.runAsRoot(() -> escalationTaskRepository.findOverdueTasks(EscalationTaskState.PENDING, now));
		for (OverdueTask task : overdue) {
			try {
				// Each step opens its own transaction, inside the task's org.
				if (OrgContext.runAs(task.orgId(), () -> escalationService.escalateTaskInNewTransaction(task.id(), now))) {
					processed++;
				}
			}
			catch (OptimisticLockingFailureException e) {
				log.info("Escalation task {} lost a race with another change to its incident; rolled back", task.id());
			}
			catch (RuntimeException e) {
				log.error("Failed to escalate task {}", task.id(), e);
			}
		}
		return processed;
	}
```

`OutboxRelay`: run as root, and tell consumers the org:

```java
	public int relayOnce() {
		return OrgContext.runAsRoot(this::relayPendingEvents);
	}

	private int relayPendingEvents() {
		// the body of the current relayOnce(), unchanged
	}
```

Move the existing loop into `relayPendingEvents()` as written (it reads `findTop50ByStatusOrderByCreatedAtAscIdAsc` and calls `markSent`, both as root). In `toMessage`, add `.setHeader("orgId", event.getOrgId())` after `.setType(...)`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='AlertWebhookOrgTest,EscalationWorkerOrgTest,OutboxRelayTest,EscalationIntegrationTest,SecurityMatrixTest'`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(tenancy): the alert webhook and the workers find each row's org (D48)"
```

---

### Task 8: Org isolation across every endpoint

**Files:**
- Test: `org/OrgIsolationTest.java`

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Write the test**

`OrgIsolationTest` runs without a test transaction (`@IntegrationTest` plus `@Transactional(propagation = Propagation.NOT_SUPPORTED)`), so each request opens its own session in its own org. In `@BeforeEach` it creates org B (`INSERT INTO organization … RETURNING id` with `JdbcTemplate`) and, inside `OrgContext.runAs(orgB, …)`, one row of every kind through the public API as B's Admin: a service, an on-call shift, an escalation policy, a tool, an incident (with a timeline entry and an escalation task), an alert through the webhook, and a notification. It keeps every id in a `Map<String, Long>`. Org A is the test org, with its own Admin, Engineer, and Viewer from `TestAccounts`. `@AfterEach` deletes everything in both orgs as root, with `SET LOCAL session_replication_role = 'replica'` around the append-only tables, as `EscalationIntegrationTest` does.

Two parameterised tests:

```java
	/** Every endpoint, called as A's Admin with B's ids, answers 404: never 403, never B's data. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("idEndpoints")
	void anotherOrgsIdsAreNotFound(String name, HttpMethod method, String pathTemplate, String body) throws Exception {
		String path = expand(pathTemplate, idsOfB);
		var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(method, path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminOfA);
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(expand(body, idsOfB));
		}
		mvc.perform(request).andExpect(status().isNotFound());
	}

	/** Every list, read as A, holds none of B's rows. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("listEndpoints")
	void listsHoldOnlyTheCallersOrg(String name, String path, String idPath, String keyOfB) throws Exception {
		String body = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminOfA))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<Number> ids = JsonPath.read(body, idPath);
		assertThat(ids).extracting(Number::longValue).doesNotContain(idsOfB.get(keyOfB));
	}
```

`idEndpoints()` lists every endpoint that takes an id: one `Arguments.of(name, method, path, body)` per mapping with a `{…}` path variable, using `{serviceId}`, `{incidentId}`, `{toolId}`, `{userId}`, `{policyServiceId}`, `{notificationId}` placeholders that `expand` fills from `idsOfB`. `listEndpoints()` lists `/api/services`, `/api/incidents`, `/api/alerts`, `/api/tools`, `/api/users`, and `/api/notifications` with the JSON path of their ids.

A third test keeps the list complete, as `SecurityMatrixTest` does: it reads every mapping from `RequestMappingHandlerMapping` whose pattern contains `{`, and fails naming any that `idEndpoints()` does not cover.

- [ ] **Step 2: Run it**

Run: `cd backend && ./mvnw -q test -Dtest=OrgIsolationTest`
Expected: all pass. A 200 means a lookup bypasses `@TenantId` (for example a membership query without the org, or `getReferenceById` with an id from the request): fix the lookup, not the test.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test
git commit -m "test(tenancy): every endpoint keeps orgs apart (D48)"
```

---

### Task 9: Sign-up page, sign in by email, org name in the header

**Files:**
- Modify: `frontend/src/api/schemas.ts`, `frontend/src/api/auth.ts`, `frontend/src/api/users.ts`, `frontend/src/auth/AuthProvider.tsx`, `frontend/src/pages/LoginPage.tsx`, `frontend/src/router.tsx`, `frontend/src/components/shell/TopBar.tsx`, `frontend/src/pages/UsersPage.tsx`, `frontend/src/pages/users/CreateUserDialog.tsx`, `frontend/src/test/fetchMock.ts`, every test that mocks `/auth/login` or `/auth/me`
- Create: `frontend/src/pages/SignupPage.tsx`, `frontend/src/pages/SignupPage.test.tsx`

**Interfaces:**
- Consumes: `POST /api/auth/signup`, `POST /api/auth/login { email, password }`, `GET /api/auth/me` → `{ user, org }`.
- Produces: `useAuth().org: Org | null`; `signup(values): Promise<User>` on the auth context.

- [ ] **Step 1: Write the failing tests**

`SignupPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch } from '@/test/fetchMock'
import { renderApp } from '@/test/render'

describe('SignupPage', () => {
  it('creates the org and lands on home', async () => {
    const fetch = mockFetch({
      'POST /api/auth/signup': {
        status: 201,
        body: {
          token: 't',
          expiresAt: '2026-11-02T11:00:00Z',
          user: { id: 1, email: 'ana@acme.test', displayName: 'Ana', role: 'ADMIN' },
          org: { id: 5, name: 'Acme', slug: 'acme' },
        },
      },
    })
    renderApp('/signup')
    await userEvent.type(screen.getByLabelText('Email'), 'ana@acme.test')
    await userEvent.type(screen.getByLabelText('Password'), 'DevPassw0rd!')
    await userEvent.type(screen.getByLabelText('Your name'), 'Ana')
    await userEvent.type(screen.getByLabelText('Org name'), 'Acme')
    await userEvent.click(screen.getByRole('button', { name: 'Create org' }))
    expect(await screen.findByText('Acme')).toBeInTheDocument()
    expect(fetch.calls('POST /api/auth/signup')[0].body).toEqual({
      email: 'ana@acme.test', password: 'DevPassw0rd!', displayName: 'Ana', orgName: 'Acme',
    })
  })

  it('shows a taken email next to the field', async () => {
    mockFetch({ 'POST /api/auth/signup': { status: 409, body: { message: 'An account with this email already exists' } } })
    renderApp('/signup')
    await userEvent.type(screen.getByLabelText('Email'), 'taken@acme.test')
    await userEvent.type(screen.getByLabelText('Password'), 'DevPassw0rd!')
    await userEvent.type(screen.getByLabelText('Your name'), 'Ana')
    await userEvent.type(screen.getByLabelText('Org name'), 'Acme')
    await userEvent.click(screen.getByRole('button', { name: 'Create org' }))
    expect(await screen.findByText('An account with this email already exists')).toBeInTheDocument()
  })
})
```

Use the helpers the existing page tests use (check `frontend/src/test/` for the render and fetch-mock helpers' exact names and call shapes, and match them; `LoginPage.test.tsx` is the closest example). In `LoginPage.test.tsx`, change the field label to `Email` and the request body to `{ email, password }`.

- [ ] **Step 2: Run them and see them fail**

Run: `cd frontend && npx vitest run src/pages/SignupPage.test.tsx src/pages/LoginPage.test.tsx`
Expected: no `/signup` route; the login form still has a Username field.

- [ ] **Step 3: Implement**

`schemas.ts`:

```ts
export const userSchema = z.object({
  id: z.number(),
  email: z.string(),
  displayName: z.string(),
  role: roleSchema,
})

export const orgSchema = z.object({ id: z.number(), name: z.string(), slug: z.string() })
export type Org = z.infer<typeof orgSchema>

export const loginResponseSchema = z.object({
  token: z.string(),
  expiresAt: timestamp,
  user: userSchema,
  org: orgSchema,
})

export const meResponseSchema = z.object({ user: userSchema, org: orgSchema })
```

(Keep `roleSchema` and `timestamp` as they are defined in the file.)

`auth.ts`:

```ts
export function login(email: string, password: string) {
  return apiFetch('/auth/login', loginResponseSchema, { method: 'POST', body: { email, password } })
}

export type SignupValues = { email: string; password: string; displayName: string; orgName: string }

export function signup(values: SignupValues) {
  return apiFetch('/auth/signup', loginResponseSchema, { method: 'POST', body: values })
}

export function fetchMe() {
  return apiFetch('/auth/me', meResponseSchema)
}
```

`AuthProvider.tsx`: keep `org` beside `user` (`const [org, setOrg] = useState<Org | null>(null)`); `fetchMe()` sets both from `{ user, org }`; `login(email, password)` and a new `signup(values)` both store the token, `setUser(response.user)`, `setOrg(response.org)`, and return the user; add `org` and `signup` to the context value and its type.

`LoginPage.tsx`: the schema field becomes `email: z.email({ error: 'Enter your email' })`, the label `Email`, `autoComplete="email"`, `type="email"`, the 401 message `Invalid email or password`, the intro `Sign in with your email and password.`, and the note at the bottom becomes a link: `New to Settle? <Link to="/signup">Create an org</Link>`.

`SignupPage.tsx`: the same two-column layout as `LoginPage`, with a form of four fields (`Email`, `Password`, `Your name`, `Org name`) validated by

```ts
const signupFormSchema = z.object({
  email: z.email({ error: 'Enter a valid email' }),
  password: z.string().min(8, { error: 'At least 8 characters' }).max(72, { error: 'At most 72 characters' }),
  displayName: z.string().trim().min(1, { error: 'Enter your name' }).max(100),
  orgName: z.string().trim().min(1, { error: 'Enter a name for your org' }).max(100),
})
```

a submit button `Create org` (`Creating…` while submitting), and on success `navigate('/home', { replace: true })`. On a request error, call `showRequestErrors(form, error, ['email', 'password', 'displayName', 'orgName'])` from `@/lib/forms`, and map a 409 to the `email` field. Below the button: `Already have an account? <Link to="/login">Sign in</Link>`.

`router.tsx`: add `{ path: '/signup', element: <SignupPage /> }` next to `/login`.

`TopBar.tsx`: in the account menu label, under the role, show the org: `<span className="text-xs font-normal text-muted-foreground">{org?.name}</span>`; and next to the breadcrumb, before the first `/`, show `{org && <span className="font-semibold text-foreground">{org.name}</span>}`. Rename the menu item `Users` to `Members`.

`UsersPage.tsx` and `CreateUserDialog.tsx`: the page title and nav label become `Members`; the dialog's `username` field becomes `email` (label `Email`, `type="email"`); the table's username column becomes `Email`. `api/users.ts` sends `email`. Update `fetchMock.ts` defaults and every test fixture that has `username` (`grep -rln username frontend/src`) to use `email`.

- [ ] **Step 4: Run the frontend checks**

Run: `cd frontend && npm run lint && npm run typecheck && npm test`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): sign up, sign in by email, and the org in the header (D48)"
```

---

### Task 10: Docs and the new rule

**Files:**
- Modify: `backend/AGENTS.md`, `README.md`, `.env.example`, `docs/1-requirements/user-stories.md`, `docs/1-requirements/srs.md`, `docs/4-testing/traceability.csv`, `docs/4-testing/test-cases.md`, `docs/2-design/architecture.md`

- [ ] **Step 1: `backend/AGENTS.md`**: add under "Code":

```markdown
- **Org-owned data is filtered by org (D48).** Every table that holds an org's data has `org_id`,
  and its entity has `@TenantId private Long orgId;`, so Hibernate fills and filters it. Set the org
  through `OrgContext` before a transaction starts: Hibernate fixes a session's org when it opens.
  Only the workers and credential lookups run as root (`OrgContext.runAsRoot`). Raw SQL is not
  filtered: add `org_id = :org` yourself. Every new endpoint goes into `OrgIsolationTest`.
```

- [ ] **Step 2: `README.md` and `.env.example`**: remove `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD`; the README's "first login" text becomes: "Open the app and choose **Create an org**. With the dev seed (D27), sign in as `admin@settle.local`, `engineer@settle.local`, or `viewer@settle.local`, password `DevPassw0rd!`."

- [ ] **Step 3: Requirements and story.** In `docs/1-requirements/srs.md`, under FR-01 add:

```markdown
- **FR-01.4** Anyone shall be able to sign up with an email and password and create an org, becoming its Admin (D48).
- **FR-01.5** Each org shall see only its own data; another org's records shall answer as not found.
- **FR-01.6** Admins shall add people to their org with a role; an org shall always keep at least one enabled Admin.
```

and a row in the change table: `| FR-01.4 to FR-01.6 | **New:** sign-up, orgs, members | D48 |`. In `docs/1-requirements/user-stories.md` add US-31 (summary row and section):

```markdown
## US-31: Sign up and create an org

**Story:** As a **new user**, I want to sign up and create an org for my team, so that we can use Settle without anyone setting it up for us.

**Priority:** Must
**Traces to:** FR-01.4 to FR-01.6, D48

**Acceptance criteria:**
- Given I sign up with an email, password, my name, and an org name, then the org exists, I am its Admin, and I am signed in.
- Given the email is already used, then sign-up is refused and nothing is created.
- Then I see only my org's services, incidents, and alerts, and other orgs never see mine.
- Then I add people to my org with a role, and the org always keeps at least one Admin.
```

Update the totals line (18 Must).

- [ ] **Step 4: `docs/4-testing/traceability.csv` and `docs/4-testing/test-cases.md`**: rows for FR-01.4 to FR-01.6, and a section "Orgs and sign-up (FR-01.4 to FR-01.6, D48)" listing `SignupControllerTest`, `OrgIsolationTest`, `TenantFilteringTest`, `OrganizationsMigrationTest`, `UserAdminControllerTest.theLastAdminCannotBeDemoted`, `IncidentControllerTest.eachOrgStartsAtInc1`, `AlertWebhookOrgTest`, `EscalationWorkerOrgTest`, and `SignupPage.test.tsx`, each `Automated`. Mark the FR rows Passed with the date and commit only after `./mvnw verify` and `npm test` pass on this branch.

- [ ] **Step 5: `docs/2-design/architecture.md`**: in §1, add a line after the overview chart: "Settle is multi-tenant: anyone signs up and creates an org, and every org-owned row carries `org_id`, filtered by Hibernate (D48, [design](../../2-design/organizations-design.md))." Replace the sentence "For this single-organization prototype, …" with "Within an org, authenticated users can read shared incident records; nobody sees another org's."

- [ ] **Step 6: Run everything once more**

Run: `cd backend && ./mvnw verify` and `cd frontend && npm run lint && npm run typecheck && npm test`
Expected: both green. Then open the pull request with `Closes` lines for the design's part 1, the test output pasted, and labels `backend`, `frontend`, `documentation`.

- [ ] **Step 7: Commit**

```bash
git add backend/AGENTS.md README.md .env.example docs
git commit -m "docs: orgs and sign-up in the rules, requirements, and tests (D48)"
```

---

## After merging

1. In Supabase, wipe the `incident` schema once so the live site starts empty: `DROP SCHEMA incident CASCADE; CREATE SCHEMA incident;` (Shane runs it; Render's next start re-runs every migration).
2. Remove `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` from Render.
3. Comment on #174, #179, #180, and #194 with the exact change each needs: rebase, add `org_id` and `@TenantId` to any table they add, and add their endpoints to `OrgIsolationTest`.
4. Update #147, #151, #159, #160, and #164 as the design's "Effect on other work" says.
