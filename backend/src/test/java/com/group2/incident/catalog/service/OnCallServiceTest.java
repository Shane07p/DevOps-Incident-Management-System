package com.group2.incident.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.service.IncidentService;
import com.group2.incident.support.IntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
    private Clock clock;

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
        ));

        OnCallScheduleResponse schedule = onCallService.getSchedule(service.getId());
        assertThat(schedule.fallbackContactId()).isEqualTo(owner.getId());
        assertThat(schedule.shifts()).hasSize(1);
        assertThat(schedule.shifts().get(0).responderId()).isEqualTo(responderA.getId());
        assertThat(schedule.shifts().get(0).startTime()).isEqualTo(T_18_00);
    }

    private AppUser createUser(String username, String displayName) {
        return users.saveAndFlush(new AppUser(username, displayName, "hash", Role.ENGINEER, clock.instant()));
    }
}
