# Design: Organizations and sign-up

**Decided 2 November 2026 (D48).** Settle becomes a product anyone can sign up for. The first person
creates an **org**, which can be a company, a team, or one person, and adds people to it. Each org
sees only its own services, incidents, and alerts.

This is part 1 of three:

| Part | What | Depends on |
| --- | --- | --- |
| **1. Orgs and sign-up** (this design) | Orgs, memberships, org-scoped data, sign-up, sign-in by email and password | Nothing |
| 2. Google sign-in | "Continue with Google" for the same accounts. GitHub sign-in comes later | Part 1 |
| 3. Invitations and passwords | Invite by email, change and reset password, email verification | Part 1, D47 (email provider) |

Later, not planned: one person in several orgs with an org switcher, GitHub sign-in, and SSO.

## Decisions

- **Many orgs, open sign-up.** Anyone can sign up and create an org. Strangers never join an existing
  org: Admins add people (part 1) or invite them (part 3).
- **Memberships now, one org on screen.** A `membership` row holds a person's role in an org. For now
  each person has one membership, so the token carries one org and there is no switcher. Allowing
  several orgs later means dropping one constraint and adding a switcher; the data does not change.
- **Email is the sign-in name.** Usernames go; display names stay.
- **Email is not verified yet.** Sign-up works with any email, behind a rate limit. Verification
  arrives with email in part 3. Google accounts are verified by Google.
- **One `org_id` column per org-owned table, filtered by Hibernate's `@TenantId`.** Rejected: a schema
  per org (Flyway and connection switching per org, awkward on Supabase's free tier), and filtering by
  hand in every query (one forgotten filter leaks another org's data).

## Data model

### New tables

- **`organization`**: `id`, `name` (1 to 100 characters), `slug` (unique; made from the name, a
  number added if taken; lower-case letters, digits, and hyphens), `next_incident_number`,
  `created_at`. The slug names the org in public links, such as the status page at `/status/{slug}`.
- **`membership`**: `id`, `org_id`, `user_id`, `role` (`ADMIN`, `ENGINEER`, `VIEWER`), `enabled`,
  `created_at`. Unique on `(org_id, user_id)`, and for now also unique on `user_id` (one org per
  person).

### Changed: `app_user`

- Adds `email`, unique regardless of case (`lower(email)`).
- Drops `username`. `display_name` stays.
- `role` and `enabled` move to `membership`.
- `password_hash` may be empty, for people who only use Google (part 2).
- `token_version` stays: logout and a role change still end every session (D21).

### `org_id` on every org-owned table

`service`, `service_sla_target`, `on_call_shift`, `escalation_policy`, `escalation_step`,
`escalation_task`, `escalation_task_step`, `incident`, `timeline_entry`, `monitoring_tool`, `alert`,
`notification`, `audit_entry`, and `outbox` get `org_id bigint NOT NULL REFERENCES organization (id)`
and an index that starts with it. Each entity has `@TenantId private Long orgId;`, so Hibernate adds
the org to every read and fills it on every write. Tables added later (Sprint 4 and on) do the same.

### Uniqueness per org

| Today | Becomes |
| --- | --- |
| Service name, case-insensitive | Unique on `(org_id, lower(name))` |
| Tool name, case-insensitive | Unique on `(org_id, lower(name))` |
| Alert `(source, event_id)` | Unique on `(org_id, source, event_id)` |
| Incident reference `INC-<id>` | A per-org `number`, unique on `(org_id, number)`, taken from `organization.next_incident_number` under a row lock. The reference becomes `INC-<number>` |
| Tool token hash | Stays unique across all orgs: the token is how the alert webhook finds the org |

## Sign-up and sign-in

- **`POST /api/auth/signup`** with `{ email, password, displayName, orgName }`: in one transaction it
  creates the user, the org, and an `ADMIN` membership, then returns a token as login does. The
  password rules are the existing ones. A taken email gives 409. Rate-limited per IP like login
  (D42).
- **`POST /api/auth/login`** with `{ email, password }`: finds the person's active membership and
  issues the token (D21) with two new claims, `org` (the org id) and the membership's `role`. A
  disabled membership, an unknown email, and a wrong password give the same 401.
- **`GET /api/auth/me`** adds the org's id, name, and slug.

## How a request knows its org

A request-scoped `OrgContext` holds the current org; Hibernate's `CurrentTenantIdentifierResolver`
reads it.

| Caller | Where the org comes from |
| --- | --- |
| Signed-in user | The `org` claim of the token |
| Alert webhook | The tool that owns the token: the tool is found by its token hash before any org is set, then the org is set from it |
| Personal access token (#151) | The token's membership, found by its hash the same way |
| Public status page (#160) | The slug in the path, `/api/public/status/{slug}` |
| Escalation worker, outbox relay | All orgs (`isRoot`); each row is then handled inside its own org |

Reading org data with no org set is an error, never "all orgs". Only the two workers run as root,
and only on purpose. Credential lookups (a tool token or a personal access token, by its hash) are
the other exception: they run before the org is known, through one explicit unscoped query each.

**Global tables.** `organization`, `app_user`, and `membership` are not `@TenantId` entities, because
sign-in has to find a person's membership before it knows the org. Every query on `membership` names
the org explicitly (the members list uses the org from `OrgContext`), and `OrgIsolationTest` covers
it.

**Raw SQL is not filtered by `@TenantId`.** None exists today. The first will be the pgvector
similarity query of #159, which must add `org_id = :org` itself; the isolation test covers it.

## Members (until invitations in part 3)

- The Users page becomes **Members**. The endpoints keep their paths (`/api/users`) and now act on the
  org's members: an Admin lists them, and adds one with an email, a starting password, and a role, as
  user administration works today.
- Disabling an account (#147) disables the membership: that person can no longer sign in to the
  org.
- The last enabled Admin of an org can't lose the Admin role or be disabled (409).
- A role change still increments the token version and logs that person out everywhere.

## Screens

- **Sign up:** email, password, display name, and org name; on success, the empty home page of the
  new org.
- **Sign in:** email instead of username, with a link to Sign up.
- **Header:** shows the org's name.
- **Members:** the Users page, renamed, as above.

## Existing rows

Production has no data worth keeping, but local databases and the startup admin have rows, and old
migrations can't be edited. One migration:

1. creates the org **"Dev org"** (slug `dev-org`) only if any user row exists;
2. sets `org_id` on every existing row to it, then makes the column required;
3. gives every existing user a membership with their current role and enabled flag, then drops
   `role`, `enabled`, and `username` from `app_user`;
4. sets each email to `<username>@settle.local`, so the dev seed users become `admin@settle.local`,
   `engineer@settle.local`, and `viewer@settle.local` (password `DevPassw0rd!`, D27);
5. keeps every incident's reference: `number` is set to its current `id`, and the org's counter starts
   after the highest;
6. replaces the global unique keys with the per-org ones above.

On an empty database it adds the columns and changes nothing else. It takes the next free migration
number when it merges, after #174 and #180 if they merge first.

**Production:** Shane wipes the Supabase `incident` schema once when this deploys, so the live site
starts empty and the first sign-up creates the first real org. `BOOTSTRAP_ADMIN_USERNAME` and
`BOOTSTRAP_ADMIN_PASSWORD` are removed from the code, Render, and `.env.example`.

## Testing

| What | Test |
| --- | --- |
| Org isolation | `OrgIsolationTest`: orgs A and B with data in every table; every endpoint, called as A with B's ids, returns 404 (never 403, which would reveal the id), and every list returns only A's rows. It lists every endpoint, as `SecurityMatrixTest` does, and fails when one is missing |
| Workers | Due escalations and pending outbox events in both orgs are handled, each inside its own org (Testcontainers) |
| Sign-up | User, org, and Admin membership created in one transaction; a taken email gives 409; two orgs with one name get different slugs; the sign-up limit gives 429 |
| Sign-in | A disabled membership and a wrong password give the same 401; the token carries `org` and `role` |
| Per-org uniqueness | Both orgs can have "Payments" and INC-1; a duplicate within one org gives 409 |
| Last Admin | Demoting or disabling the last Admin gives 409 |
| Migration | A database as V1 to V9 left it, with rows, migrates into Dev org with its memberships, emails, and incident numbers |
| Frontend | Vitest: the sign-up form, sign-in by email, and the org name in the header |

## Effect on other work

- **Order:** Shane lands this as one pull request first (about 2 to 3 days). Open pull requests
  (#174, #179, #180, #194) then rebase and add `org_id` and `@TenantId` to any table they add; each
  gets a comment with the exact change.
- **`backend/AGENTS.md` gains a rule:** every org-owned table has `org_id` and an entity with
  `@TenantId`; every new endpoint is added to `OrgIsolationTest`; raw SQL filters by `org_id`
  explicitly.
- **Issues that change:** #160 and #164 (the status page link carries the slug), #151 (a token
  belongs to a membership), #159 (the vector query filters by org), and #147 (disabling acts on the
  membership). The others are scoped automatically.
- **Documents:** D48 in the decision log; a user story US-31 (sign up and create an org); new FR-01
  sub-requirements; architecture.md §1 and the HLD show orgs; the README's sign-in instructions.
