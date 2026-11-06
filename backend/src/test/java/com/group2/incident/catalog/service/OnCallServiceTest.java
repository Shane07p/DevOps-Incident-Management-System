package com.group2.incident.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.dto.OnCallScheduleResponse;
import com.group2.incident.catalog.dto.ShiftRequest;
import com.group2.incident.catalog.dto.UpdateOnCallScheduleRequest;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.OnCallShift;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.OnCallShiftRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.service.IncidentService;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import com.group2.incident.catalog.dto.ShiftResponse;

/** Runs against PostgreSQL, so the boundary checks exercise the real on-call query. */
@IntegrationTest
class OnCallServiceTest {

    private static final Instant T_09_00 = Instant.parse("2026-10-19T09:00:00Z");
    private static final Instant T_16_59_59 = Instant.parse("2026-10-19T16:59:59Z");
    private static final Instant T_17_00 = Instant.parse("2026-10-19T17:00:00Z");
    private static final Instant T_17_30 = Instant.parse("2026-10-19T17:30:00Z");
    private static final Instant T_18_00 = Instant.parse("2026-10-19T18:00:00Z");
    private static final Instant T_22_00 = Instant.parse("2026-10-19T22:00:00Z");

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private OnCallService onCallService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private ServiceRepository services;

    @Autowired
    private OnCallShiftRepository shifts;

    @Autowired
    private AuditEntryRepository auditEntries;

    @MockitoSpyBean
    private AuditService auditService;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private AppUser owner;
    private AppUser fallbackContact;
    private AppUser responderA;
    private AppUser responderB;
    private ServiceEntity service;

    @BeforeEach
    void setUp() {
        owner = createUser("owner", "Service Owner");
        fallbackContact = createUser("fallback_lead", "Fallback Lead");
        responderA = createUser("responder_a", "Responder A");
        responderB = createUser("responder_b", "Responder B");

        ServiceEntity payments = new ServiceEntity("Payments", "Payments Service", owner, Criticality.HIGH, clock.instant());
        for (Severity severity : Severity.values()) {
            payments.putTarget(severity, 15, 240);
        }
        payments.changeFallbackContact(fallbackContact);
        service = services.saveAndFlush(payments);

        // Shift A runs 09:00 to 17:00, followed by a gap until shift B starts at 18:00.
        shifts.saveAndFlush(new OnCallShift(service, responderA, T_09_00, T_17_00));
        shifts.saveAndFlush(new OnCallShift(service, responderB, T_18_00, T_22_00));
    }

    @Test
    @DisplayName("TC-FR-15.1a: Overlapping shifts are rejected with 400; adjacent shifts are allowed")
    void shiftOverlapValidation() {
        List<ShiftRequest> overlappingShifts = List.of(
                new ShiftRequest(responderA.getId(), T_09_00, T_17_00),
                new ShiftRequest(responderB.getId(), Instant.parse("2026-10-19T16:30:00Z"), T_22_00)
        );

        assertThatThrownBy(() -> onCallService.validateShifts(overlappingShifts))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Shifts cannot overlap");

        // One ends exactly when the other begins.
        List<ShiftRequest> adjacentShifts = List.of(
                new ShiftRequest(responderA.getId(), T_09_00, T_17_00),
                new ShiftRequest(responderB.getId(), T_17_00, T_22_00)
        );

        onCallService.validateShifts(adjacentShifts);
    }

    @Test
    @DisplayName("TC-FR-15.2a: An incident created at 09:00:00 is assigned to responder A")
    void atShiftStartAssignedToResponderA() {
        assertThat(onCallService.resolveAssignee(service, T_09_00).getId()).isEqualTo(responderA.getId());
    }

    @Test
    @DisplayName("TC-FR-15.2b: An incident created at 16:59:59 is assigned to responder A")
    void justBeforeShiftEndAssignedToResponderA() {
        assertThat(onCallService.resolveAssignee(service, T_16_59_59).getId()).isEqualTo(responderA.getId());
    }

    @Test
    @DisplayName("TC-FR-15.2c: An incident created at 17:00:00 goes to the fallback contact, since it falls in the gap")
    void atShiftEndAssignedToFallbackContact() {
        assertThat(onCallService.resolveAssignee(service, T_17_00).getId()).isEqualTo(fallbackContact.getId());
    }

    @Test
    @DisplayName("TC-FR-15.2d: An incident created at 17:30, in the gap, goes to the fallback contact")
    void inGapAssignedToFallbackContact() {
        assertThat(onCallService.resolveAssignee(service, T_17_30).getId()).isEqualTo(fallbackContact.getId());
    }

    @Test
    @DisplayName("TC-FR-05.1a: An incident is assigned to whoever is on call at creation, and the assignment is on the timeline")
    void assignmentAtCreationIsOnTheTimeline() {
        Instant now = clock.instant();
        shifts.saveAndFlush(new OnCallShift(service, responderB, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))));

        IncidentResponse response = incidentService.createIncident(
                new CreateIncidentRequest("Payment gateway down", "Transactions failing", service.getId(), Severity.SEV1),
                owner.getId());

        assertThat(response.assignee().id()).isEqualTo(responderB.getId());
        assertThat(response.timeline())
                .anyMatch(entry -> entry.eventType().name().equals("ASSIGNED")
                        && entry.actor() == null
                        && entry.details().equals("Assigned to Responder B"));
    }

    @Test
    @DisplayName("updateSchedule replaces the fallback contact and every shift")
    void updateScheduleReplacesShifts() {
        onCallService.updateSchedule(service.getId(), new UpdateOnCallScheduleRequest(
                owner.getId(),
                List.of(new ShiftRequest(responderA.getId(), T_18_00, T_22_00))
        ), owner.getId());

        OnCallScheduleResponse schedule = onCallService.getSchedule(service.getId());
        assertThat(schedule.fallbackContactId()).isEqualTo(owner.getId());
        assertThat(schedule.shifts()).hasSize(1);
        assertThat(schedule.shifts().get(0).responderId()).isEqualTo(responderA.getId());
        assertThat(schedule.shifts().get(0).startTime()).isEqualTo(T_18_00);
    }

    @Test
    @DisplayName("TC-FR-13c: Adding a shift produces exactly one audit entry with actor, service, and shift details")
    void addingShiftProducesOneAuditEntry() {
        Instant t20_start = Instant.parse("2026-10-20T09:00:00Z");
        Instant t20_end = Instant.parse("2026-10-20T17:00:00Z");

        UpdateOnCallScheduleRequest request = new UpdateOnCallScheduleRequest(
                fallbackContact.getId(),
                List.of(
                        new ShiftRequest(responderA.getId(), T_09_00, T_17_00),
                        new ShiftRequest(responderB.getId(), T_18_00, T_22_00),
                        new ShiftRequest(owner.getId(), t20_start, t20_end)
                )
        );

        onCallService.updateSchedule(service.getId(), request, owner.getId());

        List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(
                AuditItemType.SERVICE, service.getId()
        );
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.getActor().getId()).isEqualTo(owner.getId());
        assertThat(entry.getItemId()).isEqualTo(service.getId());
        assertThat(entry.getAction()).isEqualTo(AuditAction.ON_CALL_SCHEDULE_UPDATED);
        assertThat(entry.getItemType()).isEqualTo(AuditItemType.SERVICE);
        assertThat(entry.getDetails())
                .contains("added shift: Service Owner")
                .doesNotContain("removed shift")
                .doesNotContain("fallback contact");
    }

    @Test
    @DisplayName("TC-FR-13c: Removing a shift produces exactly one audit entry with removed shift details")
    void removingShiftProducesOneAuditEntry() {
        // Remove shift B, keep shift A and same fallback contact
        UpdateOnCallScheduleRequest request = new UpdateOnCallScheduleRequest(
                fallbackContact.getId(),
                List.of(new ShiftRequest(responderA.getId(), T_09_00, T_17_00))
        );

        onCallService.updateSchedule(service.getId(), request, owner.getId());

        List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(
                AuditItemType.SERVICE, service.getId()
        );
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.getActor().getId()).isEqualTo(owner.getId());
        assertThat(entry.getItemId()).isEqualTo(service.getId());
        assertThat(entry.getAction()).isEqualTo(AuditAction.ON_CALL_SCHEDULE_UPDATED);
        assertThat(entry.getItemType()).isEqualTo(AuditItemType.SERVICE);
        assertThat(entry.getDetails())
                .contains("removed shift: Responder B")
                .doesNotContain("added shift")
                .doesNotContain("fallback contact");
    }

    @Test
    @DisplayName("TC-FR-13c: Changing fallback contact produces exactly one audit entry with before and after")
    void changingFallbackContactProducesOneAuditEntry() {
        // Keep both shifts identical, only change fallback from fallbackContact to owner
        UpdateOnCallScheduleRequest request = new UpdateOnCallScheduleRequest(
                owner.getId(),
                List.of(
                        new ShiftRequest(responderA.getId(), T_09_00, T_17_00),
                        new ShiftRequest(responderB.getId(), T_18_00, T_22_00)
                )
        );

        onCallService.updateSchedule(service.getId(), request, owner.getId());

        List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(
                AuditItemType.SERVICE, service.getId()
        );
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.getActor().getId()).isEqualTo(owner.getId());
        assertThat(entry.getItemId()).isEqualTo(service.getId());
        assertThat(entry.getAction()).isEqualTo(AuditAction.ON_CALL_SCHEDULE_UPDATED);
        assertThat(entry.getItemType()).isEqualTo(AuditItemType.SERVICE);
        assertThat(entry.getDetails())
                .contains("fallback contact: Fallback Lead → Service Owner")
                .doesNotContain("added shift")
                .doesNotContain("removed shift");
    }

    @Test
    @DisplayName("TC-FR-13c: Changing shifts and fallback contact together produces exactly one audit entry with all changes")
    void changingShiftsAndFallbackTogetherProducesOneAuditEntry() {
        Instant t20_start = Instant.parse("2026-10-20T09:00:00Z");
        Instant t20_end = Instant.parse("2026-10-20T17:00:00Z");

        // Change fallback to owner, remove shift B, add new shift for owner
        UpdateOnCallScheduleRequest request = new UpdateOnCallScheduleRequest(
                owner.getId(),
                List.of(
                        new ShiftRequest(responderA.getId(), T_09_00, T_17_00),
                        new ShiftRequest(owner.getId(), t20_start, t20_end)
                )
        );

        onCallService.updateSchedule(service.getId(), request, owner.getId());

        List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(
                AuditItemType.SERVICE, service.getId()
        );
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.getActor().getId()).isEqualTo(owner.getId());
        assertThat(entry.getItemId()).isEqualTo(service.getId());
        assertThat(entry.getAction()).isEqualTo(AuditAction.ON_CALL_SCHEDULE_UPDATED);
        assertThat(entry.getItemType()).isEqualTo(AuditItemType.SERVICE);
        assertThat(entry.getDetails())
                .contains("fallback contact: Fallback Lead → Service Owner")
                .contains("added shift: Service Owner")
                .contains("removed shift: Responder B");
    }

    @Test
    @DisplayName("TC-FR-13d: Unchanged schedule produces zero audit entries")
    void unchangedScheduleProducesZeroAuditEntries() {
        // Send the exact same shifts (even in reverse order) with the same fallback contact
        UpdateOnCallScheduleRequest request = new UpdateOnCallScheduleRequest(
                fallbackContact.getId(),
                List.of(
                        new ShiftRequest(responderB.getId(), T_18_00, T_22_00),
                        new ShiftRequest(responderA.getId(), T_09_00, T_17_00)
                )
        );

        onCallService.updateSchedule(service.getId(), request, owner.getId());

        List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(
                AuditItemType.SERVICE, service.getId()
        );
        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("TC-FR-13e: Schedule validation failure leaves no audit entry")
    void scheduleValidationFailureLeavesNoAuditEntry() {
        // Overlapping shifts
        UpdateOnCallScheduleRequest request = new UpdateOnCallScheduleRequest(
                fallbackContact.getId(),
                List.of(
                        new ShiftRequest(responderA.getId(), T_09_00, T_17_00),
                        new ShiftRequest(responderB.getId(), Instant.parse("2026-10-19T16:30:00Z"), T_22_00)
                )
        );

        assertThatThrownBy(() -> onCallService.updateSchedule(service.getId(), request, owner.getId()))
                .isInstanceOf(FieldValidationException.class);

        List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(
                AuditItemType.SERVICE, service.getId()
        );
        assertThat(entries).isEmpty();
    }

    // Outside the test transaction, so the save really commits or rolls back; setUp's rows are
    // committed too and deleted at the end.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("TC-FR-13e: When the audit entry fails, the schedule change rolls back with it")
    void auditFailureRollsBackScheduleChanges() {
        try {
            AuditService target = AopTestUtils.getUltimateTargetObject(auditService);
            doThrow(new RuntimeException("Simulated audit database error"))
                    .when(target)
                    .record(any(), eq(AuditAction.ON_CALL_SCHEDULE_UPDATED), any(), anyLong(), anyString());

            UpdateOnCallScheduleRequest request = new UpdateOnCallScheduleRequest(
                    owner.getId(),
                    List.of(new ShiftRequest(responderA.getId(), T_18_00, T_22_00))
            );

            assertThatThrownBy(() -> onCallService.updateSchedule(service.getId(), request, owner.getId()))
                    .hasMessage("Simulated audit database error");

            assertThat(services.findWithDetailsById(service.getId()).orElseThrow().getFallbackContact().getId())
                    .isEqualTo(fallbackContact.getId());
            assertThat(shifts.findByServiceIdOrderByStartTimeAsc(service.getId()))
                    .extracting(OnCallShift::getStartTime)
                    .containsExactly(T_09_00, T_18_00);
            assertThat(auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(AuditItemType.SERVICE, service.getId()))
                    .isEmpty();
        }
        finally {
            shifts.deleteAllInBatch();
            services.deleteById(service.getId());
            users.deleteAll(List.of(owner, fallbackContact, responderA, responderB));
        }
    }

    // Outside the test transaction: the first save holds its transaction open while a second save
    // starts, as when two Admins save one schedule at the same moment.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Two saves of one schedule at once leave one of them, never a mix of both")
    void concurrentScheduleSavesDoNotMix() throws Exception {
        shifts.deleteAllInBatch();
        UpdateOnCallScheduleRequest first = new UpdateOnCallScheduleRequest(fallbackContact.getId(),
                List.of(new ShiftRequest(responderA.getId(), T_09_00, T_17_00)));
        UpdateOnCallScheduleRequest second = new UpdateOnCallScheduleRequest(fallbackContact.getId(),
                List.of(new ShiftRequest(responderB.getId(), Instant.parse("2026-10-19T12:00:00Z"), T_22_00)));
        // The other thread works for the same org as the test (D48).
        long org = OrgContext.current();
        ExecutorService otherAdmin = Executors.newSingleThreadExecutor();
        try {
            Future<OnCallScheduleResponse> secondSave = new TransactionTemplate(transactionManager).execute(status -> {
                onCallService.updateSchedule(service.getId(), first, owner.getId());
                Future<OnCallScheduleResponse> started = otherAdmin.submit(() -> OrgContext.runAs(org, () -> onCallService.updateSchedule(service.getId(), second, owner.getId())));
                waitForASessionBlockedOnALock();
                return started;
            });
            secondSave.get(30, TimeUnit.SECONDS);

            assertThat(onCallService.getSchedule(service.getId()).shifts())
                    .extracting(ShiftResponse::responderId)
                    .containsExactly(responderB.getId());
        }
        finally {
            otherAdmin.shutdownNow();
            deleteAuditEntriesOf(service.getId());
            shifts.deleteAllInBatch();
            services.deleteById(service.getId());
            users.deleteAll(List.of(owner, fallbackContact, responderA, responderB));
        }
    }

    // Both saves commit an audit entry naming the owner, which would block deleting the owner. The
    // log is append-only, so replica mode skips its trigger for this one delete (see AlertGroupingTest).
    private void deleteAuditEntriesOf(Long serviceId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = 'replica'");
            jdbc.update("DELETE FROM audit_entry WHERE item_type = 'SERVICE' AND item_id = ?", serviceId);
            jdbc.execute("SET LOCAL session_replication_role = 'origin'");
        });
    }

    // Up to two seconds for the second save to reach the lock, so every run sees the same order.
    private void waitForASessionBlockedOnALock() {
        for (int i = 0; i < 40; i++) {
            Integer blocked = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock'", Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private AppUser createUser(String username, String displayName) {
        return accounts.save(new AppUser(username + "@test.local", displayName, "hash", clock.instant()), Role.ENGINEER);
    }
}
