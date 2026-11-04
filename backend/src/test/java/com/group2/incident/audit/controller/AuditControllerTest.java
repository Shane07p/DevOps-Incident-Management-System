package com.group2.incident.audit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.audit.service.AuditQueryService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for GET /api/audit (Issue #153, FR-13).
 * All tests run against real PostgreSQL via @IntegrationTest / Testcontainers.
 */
@IntegrationTest
class AuditControllerTest {

    // Fixed UTC instants so ordering tests are deterministic.
    private static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-01-03T10:00:00Z");
    private static final Instant T4 = Instant.parse("2026-01-04T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private AuditEntryRepository auditEntries;

    private String adminToken;
    private AppUser admin;
    private AppUser engineer;

    @BeforeEach
    void setUp() throws Exception {
        admin = accounts.create("audit_admin@test.local", Role.ADMIN);
        engineer = accounts.create("audit_eng@test.local", Role.ENGINEER);
        adminToken = accounts.token("audit_admin@test.local");
    }

    // Who may call GET /api/audit is checked once, in SecurityMatrixTest.

    @Test
    void mutationMethodsAreNotExposed() throws Exception {
        mvc.perform(post("/api/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed());
    }

    // ── Response shape ────────────────────────────────────────────────────────

    @Test
    void adminReadsAuditEntriesNewestFirst() throws Exception {
        AuditEntry older = saveEntry(admin, AuditAction.USER_CREATED, AuditItemType.USER, 1L, "Older entry", T1);
        AuditEntry newer = saveEntry(admin, AuditAction.SERVICE_CREATED, AuditItemType.SERVICE, 2L, "Newer entry", T2);

        String body = mvc.perform(get("/api/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Long> ids = JsonPath.read(body, "$.content[*].id");
        // Newer entry must appear before older entry.
        assertThat(ids.indexOf(newer.getId().intValue()))
                .isLessThan(ids.indexOf(older.getId().intValue()));
    }

    @Test
    void responseContainsAllRequiredFields() throws Exception {
        saveEntry(admin, AuditAction.SERVICE_CREATED, AuditItemType.SERVICE, 99L, "name: Payments", T1);

        mvc.perform(get("/api/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").isNumber())
                .andExpect(jsonPath("$.content[0].actorDisplayName").value("audit_admin"))
                .andExpect(jsonPath("$.content[0].action").value("SERVICE_CREATED"))
                .andExpect(jsonPath("$.content[0].itemType").value("SERVICE"))
                .andExpect(jsonPath("$.content[0].itemId").value(99))
                .andExpect(jsonPath("$.content[0].details").value("name: Payments"))
                .andExpect(jsonPath("$.content[0].recordedAt").isString())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(AuditQueryService.PAGE_SIZE))
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    @Test
    void systemGeneratedEntryWithNullActorIsHandledCorrectly() throws Exception {
        // actor = null simulates a system-generated event (e.g. scheduled escalation).
        saveEntry(null, AuditAction.INCIDENT_CREATED, AuditItemType.INCIDENT, 1L, "System action", T1);

        mvc.perform(get("/api/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actorDisplayName").isEmpty());
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    @Test
    void auditEntriesArePaginatedInPagesOfFifty() throws Exception {
        // Seed 51 entries so that two pages are needed.
        for (int i = 0; i < 51; i++) {
            saveEntry(admin, AuditAction.USER_CREATED, AuditItemType.USER, (long) i,
                    "entry-" + i, T1.plusSeconds(i));
        }

        String page0 = mvc.perform(get("/api/audit?page=0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String page1 = mvc.perform(get("/api/audit?page=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int page0Size = ((List<?>) JsonPath.read(page0, "$.content")).size();
        int page1Size = ((List<?>) JsonPath.read(page1, "$.content")).size();
        long total = ((Number) JsonPath.read(page0, "$.totalElements")).longValue();

        assertThat(page0Size).isEqualTo(AuditQueryService.PAGE_SIZE);
        assertThat(page1Size).isEqualTo(1);
        assertThat(total).isEqualTo(51L);
    }

    @Test
    void pageBoundariesDoNotDuplicateOrSkipRecords() throws Exception {
        // 52 records, deterministic timestamps and IDs.
        List<Long> expectedIds = new ArrayList<>();
        for (int i = 0; i < 52; i++) {
            AuditEntry e = saveEntry(admin, AuditAction.USER_CREATED, AuditItemType.USER, (long) i,
                    "entry-" + i, T1.plusSeconds(i));
            expectedIds.add(0, e.getId()); // reverse order = newest first
        }

        String page0 = mvc.perform(get("/api/audit?page=0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        String page1 = mvc.perform(get("/api/audit?page=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();

        List<Integer> ids0 = JsonPath.read(page0, "$.content[*].id");
        List<Integer> ids1 = JsonPath.read(page1, "$.content[*].id");

        List<Long> allReturnedIds = new ArrayList<>();
        ids0.forEach(i -> allReturnedIds.add(i.longValue()));
        ids1.forEach(i -> allReturnedIds.add(i.longValue()));

        assertThat(allReturnedIds).hasSize(52);
        // No ID appears in both pages.
        assertThat(allReturnedIds).doesNotHaveDuplicates();
        // The first page and second page together cover the expected set.
        assertThat(allReturnedIds).containsExactlyElementsOf(expectedIds);
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    @Test
    void filtersByItemType() throws Exception {
        saveEntry(admin, AuditAction.USER_CREATED, AuditItemType.USER, 1L, "user entry", T1);
        saveEntry(admin, AuditAction.SERVICE_CREATED, AuditItemType.SERVICE, 2L, "service entry", T2);

        mvc.perform(get("/api/audit?itemType=USER")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].itemType").value("USER"));
    }

    @Test
    void filtersByItemId() throws Exception {
        saveEntry(admin, AuditAction.USER_CREATED, AuditItemType.USER, 10L, "for item 10", T1);
        saveEntry(admin, AuditAction.SERVICE_CREATED, AuditItemType.SERVICE, 20L, "for item 20", T2);

        mvc.perform(get("/api/audit?itemId=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].itemId").value(10));
    }

    @Test
    void filtersByActor() throws Exception {
        saveEntry(admin, AuditAction.USER_CREATED, AuditItemType.USER, 1L, "by admin", T1);
        saveEntry(engineer, AuditAction.SERVICE_CREATED, AuditItemType.SERVICE, 2L, "by engineer", T2);

        mvc.perform(get("/api/audit?actorId=" + admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorDisplayName").value("audit_admin"));
    }

    @Test
    void filtersByTimeRange() throws Exception {
        saveEntry(admin, AuditAction.USER_CREATED, AuditItemType.USER, 1L, "before range", T1);
        saveEntry(admin, AuditAction.SERVICE_CREATED, AuditItemType.SERVICE, 2L, "in range", T2);
        saveEntry(admin, AuditAction.INCIDENT_CREATED, AuditItemType.INCIDENT, 3L, "after range", T3);

        // from T2 inclusive, to T3 exclusive → only T2 matches.
        mvc.perform(get("/api/audit?from=2026-01-02T10:00:00Z&to=2026-01-03T10:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].details").value("in range"));
    }

    @Test
    void combinesFiltersUsingAndSemantics() throws Exception {
        saveEntry(admin, AuditAction.USER_CREATED, AuditItemType.USER, 1L, "admin user", T1);
        saveEntry(admin, AuditAction.SERVICE_CREATED, AuditItemType.SERVICE, 2L, "admin service", T2);
        saveEntry(engineer, AuditAction.USER_CREATED, AuditItemType.USER, 3L, "engineer user", T3);

        // Only admin + USER type → exactly 1 result.
        mvc.perform(get("/api/audit?itemType=USER&actorId=" + admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].details").value("admin user"));
    }

    @Test
    void emptyResultsReturnAnEmptyPage() throws Exception {
        // No entries seeded at all.
        mvc.perform(get("/api/audit?itemType=TOOL")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    void invalidPageAndTimeRangeReturn400() throws Exception {
        // Negative page.
        mvc.perform(get("/api/audit?page=-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.page").value("must not be negative"));

        // from >= to.
        mvc.perform(get("/api/audit?from=2026-01-03T00:00:00Z&to=2026-01-02T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.from").isString());

        // Unparseable timestamp.
        mvc.perform(get("/api/audit?from=not-a-date")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.from").isString());

        // Unknown itemType.
        mvc.perform(get("/api/audit?itemType=UNKNOWN_TYPE")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.itemType").isString());
    }

    // ── Ordering stability ────────────────────────────────────────────────────

    @Test
    void equalTimestampsDeterministicOrdering() throws Exception {
        // Two entries with the same recordedAt — ordering falls back to id DESC.
        AuditEntry first = saveEntry(admin, AuditAction.USER_CREATED, AuditItemType.USER, 1L, "first by id", T1);
        AuditEntry second = saveEntry(admin, AuditAction.SERVICE_CREATED, AuditItemType.SERVICE, 2L, "second by id", T1);

        String body = mvc.perform(get("/api/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Integer> ids = JsonPath.read(body, "$.content[*].id");
        // Higher ID (second) should come before lower ID (first) because sort is id DESC.
        assertThat(ids.indexOf(second.getId().intValue()))
                .isLessThan(ids.indexOf(first.getId().intValue()));
    }

    // ── Read-only guarantee ───────────────────────────────────────────────────

    @Test
    void readingAuditLogDoesNotChangeExistingEntries() throws Exception {
        AuditEntry entry = saveEntry(admin, AuditAction.USER_CREATED, AuditItemType.USER, 1L, "original details", T1);
        long countBefore = auditEntries.count();

        // Call the endpoint twice.
        mvc.perform(get("/api/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        long countAfter = auditEntries.count();
        assertThat(countAfter).isEqualTo(countBefore);

        AuditEntry fetched = auditEntries.findById(entry.getId()).orElseThrow();
        assertThat(fetched.getDetails()).isEqualTo("original details");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AuditEntry saveEntry(AppUser actor, AuditAction action, AuditItemType itemType,
                                 long itemId, String details, Instant recordedAt) {
        return auditEntries.saveAndFlush(new AuditEntry(actor, action, itemType, itemId, details, recordedAt));
    }
}
