package com.group2.incident.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.group2.incident.catalog.entity.SlaTarget;
import com.group2.incident.catalog.repository.OnCallShiftRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.Severity;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.incident.service.IncidentService;
import com.group2.incident.notification.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnCallServiceTest {

    private static final Instant T_09_00 = Instant.parse("2026-10-19T09:00:00Z");
    private static final Instant T_16_59_59 = Instant.parse("2026-10-19T16:59:59Z");
    private static final Instant T_17_00 = Instant.parse("2026-10-19T17:00:00Z");
    private static final Instant T_17_30 = Instant.parse("2026-10-19T17:30:00Z");
    private static final Instant T_18_00 = Instant.parse("2026-10-19T18:00:00Z");
    private static final Instant T_22_00 = Instant.parse("2026-10-19T22:00:00Z");

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private OnCallShiftRepository onCallShiftRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private TimelineEntryRepository timelineEntryRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    private Clock clock;
    private OnCallService onCallService;
    private IncidentService incidentService;

    private AppUser owner;
    private AppUser fallbackContact;
    private AppUser responderA;
    private AppUser responderB;
    private ServiceEntity service;

    private OnCallShift shiftA;
    private OnCallShift shiftB;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(T_09_00, ZoneOffset.UTC);
        onCallService = new OnCallService(serviceRepository, onCallShiftRepository, appUserRepository, clock);

        incidentService = new IncidentService(
                incidentRepository,
                timelineEntryRepository,
                serviceRepository,
                appUserRepository,
                auditService,
                onCallService,
                notificationService,
                clock
        );

        owner = new AppUser(1L, "owner", "Service Owner", "hash", Role.ENGINEER, T_09_00);
        fallbackContact = new AppUser(2L, "fallback_lead", "Fallback Lead", "hash", Role.ENGINEER, T_09_00);
        responderA = new AppUser(3L, "responder_a", "Responder A", "hash", Role.ENGINEER, T_09_00);
        responderB = new AppUser(4L, "responder_b", "Responder B", "hash", Role.ENGINEER, T_09_00);

        service = new ServiceEntity(10L, "Payments", "Payments Service", owner, fallbackContact,
                Criticality.HIGH, Map.of(
                Severity.SEV1, new SlaTarget(15, 240),
                Severity.SEV2, new SlaTarget(60, 480)
        ), T_09_00);

        shiftA = new OnCallShift(101L, service, responderA, T_09_00, T_17_00);
        shiftB = new OnCallShift(102L, service, responderB, T_18_00, T_22_00);
    }

    @Test
    @DisplayName("TC-FR-15.1a: Overlapping shifts are rejected with 400; adjacent shifts are allowed")
    void shiftOverlapValidation() {
        // Overlapping shifts (Shift 1: 09:00-17:00, Shift 2: 16:30-20:00)
        List<ShiftRequest> overlappingShifts = List.of(
                new ShiftRequest(responderA.getId(), T_09_00, T_17_00),
                new ShiftRequest(responderB.getId(), Instant.parse("2026-10-19T16:30:00Z"), T_22_00)
        );

        assertThatThrownBy(() -> onCallService.validateShifts(overlappingShifts))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Shifts cannot overlap");

        // Adjacent shifts (Shift 1: 09:00-17:00, Shift 2: 17:00-22:00 where one ends exactly when the other begins)
        List<ShiftRequest> adjacentShifts = List.of(
                new ShiftRequest(responderA.getId(), T_09_00, T_17_00),
                new ShiftRequest(responderB.getId(), T_17_00, T_22_00)
        );

        // Should not throw
        onCallService.validateShifts(adjacentShifts);
    }

    @Test
    @DisplayName("TC-FR-15.2a: Boundary value - incident created at 09:00:00 is assigned to responder A")
    void incidentCreatedAtShiftStartAssignedToResponderA() {
        when(onCallShiftRepository.findActiveShiftForService(service.getId(), T_09_00))
                .thenReturn(Optional.of(shiftA));

        AppUser assignee = onCallService.resolveAssignee(service, T_09_00);

        assertThat(assignee).isNotNull();
        assertThat(assignee.getUsername()).isEqualTo("responder_a");
    }

    @Test
    @DisplayName("TC-FR-15.2b: Boundary value - incident created at 16:59:59 is assigned to responder A")
    void incidentCreatedAtShiftEndMinusOneSecondAssignedToResponderA() {
        when(onCallShiftRepository.findActiveShiftForService(service.getId(), T_16_59_59))
                .thenReturn(Optional.of(shiftA));

        AppUser assignee = onCallService.resolveAssignee(service, T_16_59_59);

        assertThat(assignee).isNotNull();
        assertThat(assignee.getUsername()).isEqualTo("responder_a");
    }

    @Test
    @DisplayName("TC-FR-15.2c: Boundary value - incident created at 17:00:00 is assigned to fallback contact (in gap)")
    void incidentCreatedAtExactShiftEndAssignedToFallbackContact() {
        when(onCallShiftRepository.findActiveShiftForService(service.getId(), T_17_00))
                .thenReturn(Optional.empty());

        AppUser assignee = onCallService.resolveAssignee(service, T_17_00);

        assertThat(assignee).isNotNull();
        assertThat(assignee.getUsername()).isEqualTo("fallback_lead");
    }

    @Test
    @DisplayName("TC-FR-15.2d: Equivalence - incident created at 17:30 (in the gap) is assigned to fallback contact")
    void incidentCreatedAtGapAssignedToFallbackContact() {
        when(onCallShiftRepository.findActiveShiftForService(service.getId(), T_17_30))
                .thenReturn(Optional.empty());

        AppUser assignee = onCallService.resolveAssignee(service, T_17_30);

        assertThat(assignee).isNotNull();
        assertThat(assignee.getUsername()).isEqualTo("fallback_lead");
    }

    @Test
    @DisplayName("TC-FR-05.1a: Any assignment at creation puts assignment on the timeline and notifies assignee")
    void assignmentAtCreationRecordsTimelineAndNotifiesAssignee() {
        when(appUserRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(serviceRepository.findWithDetailsById(service.getId())).thenReturn(Optional.of(service));
        when(onCallShiftRepository.findActiveShiftForService(service.getId(), T_09_00))
                .thenReturn(Optional.of(shiftA));

        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> {
            Incident inc = inv.getArgument(0);
            return new Incident(1L, inc.getTitle(), inc.getDescription(), inc.getService(),
                    inc.getSeverity(), inc.getStatus(), inc.getReporter(), inc.getAssignee(),
                    inc.getCreatedAt(), inc.getResponseTargetMinutes(), inc.getResolutionTargetMinutes());
        });

        List<TimelineEntry> capturedTimeline = new ArrayList<>();
        long[] idSeq = new long[]{1L};
        when(timelineEntryRepository.save(any(TimelineEntry.class))).thenAnswer(inv -> {
            TimelineEntry te = inv.getArgument(0);
            TimelineEntry saved = new TimelineEntry(idSeq[0]++, te.getIncident(), te.getActor(), te.getEventType(),
                    te.getDetails(), te.getRecordedAt(), te.getCorrectsEntry());
            capturedTimeline.add(saved);
            return saved;
        });

        CreateIncidentRequest request = new CreateIncidentRequest(
                "Payment gateway down",
                "Transactions failing",
                service.getId(),
                Severity.SEV1
        );

        IncidentResponse response = incidentService.createIncident(request, owner.getId());

        // Verify incident assignee
        assertThat(response.assignee()).isNotNull();
        assertThat(response.assignee().displayName()).isEqualTo("Responder A");

        // Verify timeline contains assignment event (TC-FR-05.1a)
        assertThat(capturedTimeline)
                .anyMatch(entry -> entry.getEventType() == TimelineEventType.ASSIGNED
                        && entry.getDetails().contains("Assigned to responder_a"));

        // Verify assignee is notified (FR-07.1 / TC-FR-05.1a)
        verify(notificationService).notifyUser(eq(responderA), contains("Payment gateway down"));
    }

    @Test
    @DisplayName("updateSchedule updates fallback contact and shifts")
    void updateScheduleSuccess() {
        when(serviceRepository.findById(10L)).thenReturn(Optional.of(service));
        when(appUserRepository.findById(2L)).thenReturn(Optional.of(fallbackContact));
        when(appUserRepository.findById(3L)).thenReturn(Optional.of(responderA));
        when(onCallShiftRepository.save(any(OnCallShift.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateOnCallScheduleRequest request = new UpdateOnCallScheduleRequest(
                2L,
                List.of(new ShiftRequest(3L, T_09_00, T_17_00))
        );

        OnCallScheduleResponse response = onCallService.updateSchedule(10L, request);

        assertThat(response.serviceId()).isEqualTo(10L);
        assertThat(response.fallbackContactId()).isEqualTo(2L);
        assertThat(response.shifts()).hasSize(1);
        assertThat(response.shifts().get(0).responderUsername()).isEqualTo("responder_a");
        verify(onCallShiftRepository).deleteByServiceId(10L);
    }
}
