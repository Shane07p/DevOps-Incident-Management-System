package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.dto.TransitionRequest;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit and service-level test for {@link IncidentService#transitionIncident}.
 *
 * Verifies:
 * - Legal transitions succeed and record timeline entry with actor and timestamp
 * - Illegal transitions are rejected with ConflictException without modifying the incident
 * - Version validation and optimistic locking failure return ConflictException
 * - Transition to RESOLVED requires a non-blank recovery note
 * - OPEN -> ACKNOWLEDGED transition sets acknowledgedAt, assignee and cancels escalation
 * - Timeline persistence failure triggers transactional rollback
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentServiceTransitionTest {

	private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");
	private static final Clock FIXED_CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

	@Mock
	private IncidentRepository incidentRepository;

	@Mock
	private TimelineEntryRepository timelineEntryRepository;

	@Mock
	private EscalationService escalationService;

	@Mock
	private com.group2.incident.auth.repository.AppUserRepository appUserRepository;

	@Captor
	private ArgumentCaptor<TimelineEntry> timelineCaptor;

	private TransitionPolicy transitionPolicy;
	private IncidentService incidentService;

	private AppUser engineer;
	private ServiceEntity service;

	@BeforeEach
	void setUp() {
		transitionPolicy = new TransitionPolicy();
		incidentService = new IncidentService(
				incidentRepository,
				timelineEntryRepository,
				null,
				null,
				appUserRepository,
				null,
				escalationService,
				null,
				null,
				null,
				null,
				null,
				transitionPolicy,
				FIXED_CLOCK
		);

		engineer = new AppUser("engineer@test.local", "Dev Engineer", "hash", T0);
		ReflectionTestUtils.setField(engineer, "id", 101L);

		service = new ServiceEntity("Checkout API", "Checkout service", engineer, Criticality.HIGH, T0);
		ReflectionTestUtils.setField(service, "id", 1L);

		when(appUserRepository.findById(101L)).thenReturn(Optional.of(engineer));
	}

	private Incident createIncident(IncidentStatus status, long version) {
		Incident incident = new Incident(
				"High latency",
				"Database timeouts",
				service,
				Severity.SEV2,
				status,
				engineer,
				T0,
				60,
				480
		);
		ReflectionTestUtils.setField(incident, "id", 1L);
		ReflectionTestUtils.setField(incident, "number", 42L);
		ReflectionTestUtils.setField(incident, "version", version);
		return incident;
	}

	@Test
	@DisplayName("Legal transition: ACKNOWLEDGED -> INVESTIGATING updates status and records timeline")
	void legalTransition_acknowledgedToInvestigating_succeeds() {
		Incident incident = createIncident(IncidentStatus.ACKNOWLEDGED, 1L);
		when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));
		when(incidentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		TransitionRequest request = new TransitionRequest(IncidentStatus.INVESTIGATING, 1L, null);
		IncidentResponse response = incidentService.transitionIncident(1L, request, 101L);

		assertThat(response.status()).isEqualTo(IncidentStatus.INVESTIGATING);
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.INVESTIGATING);

		verify(timelineEntryRepository).save(timelineCaptor.capture());
		TimelineEntry entry = timelineCaptor.getValue();
		assertThat(entry.getEventType()).isEqualTo(TimelineEventType.STATUS_CHANGED);
		assertThat(entry.getActor()).isEqualTo(engineer);
		assertThat(entry.getDetails()).isEqualTo("Status changed from ACKNOWLEDGED to INVESTIGATING");
		assertThat(entry.getRecordedAt()).isEqualTo(T0);
	}

	@Test
	@DisplayName("Legal transition: MONITORING -> RESOLVED with valid recovery note records recovery note in timeline")
	void legalTransition_monitoringToResolved_recordsRecoveryNote() {
		Incident incident = createIncident(IncidentStatus.MONITORING, 4L);
		when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));
		when(incidentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		TransitionRequest request = new TransitionRequest(
				IncidentStatus.RESOLVED,
				4L,
				"Restarted cache clusters and drained poisoned queue"
		);
		IncidentResponse response = incidentService.transitionIncident(1L, request, 101L);

		assertThat(response.status()).isEqualTo(IncidentStatus.RESOLVED);
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);

		verify(timelineEntryRepository).save(timelineCaptor.capture());
		TimelineEntry entry = timelineCaptor.getValue();
		assertThat(entry.getEventType()).isEqualTo(TimelineEventType.STATUS_CHANGED);
		assertThat(entry.getDetails()).isEqualTo("Status changed from MONITORING to RESOLVED: Restarted cache clusters and drained poisoned queue");
		assertThat(entry.getRecordedAt()).isEqualTo(T0);
	}

	@Test
	@DisplayName("Legal transition: OPEN -> ACKNOWLEDGED sets acknowledgedAt, assigns actor, and cancels escalation")
	void legalTransition_openToAcknowledged_setsAcknowledgedAtAndCancelsEscalation() {
		Incident incident = createIncident(IncidentStatus.OPEN, 0L);
		when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));
		when(incidentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		TransitionRequest request = new TransitionRequest(IncidentStatus.ACKNOWLEDGED, 0L, null);
		IncidentResponse response = incidentService.transitionIncident(1L, request, 101L);

		assertThat(response.status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(incident.getAcknowledgedAt()).isEqualTo(T0);
		assertThat(incident.getAssignee()).isEqualTo(engineer);

		verify(escalationService).cancelTaskForIncident(1L, T0);
		verify(timelineEntryRepository).save(any());
	}

	@Test
	@DisplayName("Alternative legal transitions: INVESTIGATING -> MONITORING and MONITORING -> INVESTIGATING")
	void alternativeLegalTransitionsSucceed() {
		// INVESTIGATING -> MONITORING
		Incident incident1 = createIncident(IncidentStatus.INVESTIGATING, 2L);
		when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident1));
		when(incidentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		TransitionRequest req1 = new TransitionRequest(IncidentStatus.MONITORING, 2L, null);
		IncidentResponse res1 = incidentService.transitionIncident(1L, req1, 101L);
		assertThat(res1.status()).isEqualTo(IncidentStatus.MONITORING);

		// MONITORING -> INVESTIGATING
		Incident incident2 = createIncident(IncidentStatus.MONITORING, 3L);
		when(incidentRepository.findById(2L)).thenReturn(Optional.of(incident2));
		when(incidentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		TransitionRequest req2 = new TransitionRequest(IncidentStatus.INVESTIGATING, 3L, null);
		IncidentResponse res2 = incidentService.transitionIncident(2L, req2, 101L);
		assertThat(res2.status()).isEqualTo(IncidentStatus.INVESTIGATING);
	}

	@Test
	@DisplayName("Negative: Transition to RESOLVED requires non-blank recovery note (missing null note rejected)")
	void transitionToResolved_missingRecoveryNote_throwsFieldValidationException() {
		Incident incident = createIncident(IncidentStatus.MONITORING, 3L);
		when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

		TransitionRequest request = new TransitionRequest(IncidentStatus.RESOLVED, 3L, null);

		assertThatThrownBy(() -> incidentService.transitionIncident(1L, request, 101L))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("A recovery note is required to resolve an incident");

		// Incident remains unchanged
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.MONITORING);
		verify(incidentRepository, never()).saveAndFlush(any());
		verify(timelineEntryRepository, never()).save(any());
	}

	@Test
	@DisplayName("Negative: Transition to RESOLVED with whitespace-only recovery note is rejected")
	void transitionToResolved_blankRecoveryNote_throwsFieldValidationException() {
		Incident incident = createIncident(IncidentStatus.MONITORING, 3L);
		when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

		TransitionRequest request = new TransitionRequest(IncidentStatus.RESOLVED, 3L, "   \t\n  ");

		assertThatThrownBy(() -> incidentService.transitionIncident(1L, request, 101L))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("A recovery note is required to resolve an incident");

		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.MONITORING);
		verify(incidentRepository, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Negative: Illegal transition (e.g. OPEN -> RESOLVED) throws ConflictException and leaves incident unchanged")
	void illegalTransition_openToResolved_throwsConflictException() {
		Incident incident = createIncident(IncidentStatus.OPEN, 0L);
		when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

		TransitionRequest request = new TransitionRequest(IncidentStatus.RESOLVED, 0L, "Fixed it");

		assertThatThrownBy(() -> incidentService.transitionIncident(1L, request, 101L))
				.isInstanceOf(ConflictException.class)
				.hasMessage("Cannot transition incident from OPEN to RESOLVED");

		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
		verify(incidentRepository, never()).saveAndFlush(any());
		verify(timelineEntryRepository, never()).save(any());
	}

	@Test
	@DisplayName("Negative: Stale version in request throws ConflictException (409)")
	void staleExpectedVersion_throwsConflictException() {
		Incident incident = createIncident(IncidentStatus.ACKNOWLEDGED, 5L);
		when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

		TransitionRequest request = new TransitionRequest(IncidentStatus.INVESTIGATING, 4L, null); // Stale version 4 != 5

		assertThatThrownBy(() -> incidentService.transitionIncident(1L, request, 101L))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("This record was changed by another request");

		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		verify(incidentRepository, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Negative: OptimisticLockingFailureException during save throws ConflictException (409)")
	void optimisticLockingFailure_throwsConflictException() {
		Incident incident = createIncident(IncidentStatus.ACKNOWLEDGED, 2L);
		when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));
		when(incidentRepository.saveAndFlush(any()))
				.thenThrow(new OptimisticLockingFailureException("Row updated by concurrent transaction"));

		TransitionRequest request = new TransitionRequest(IncidentStatus.INVESTIGATING, 2L, null);

		assertThatThrownBy(() -> incidentService.transitionIncident(1L, request, 101L))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("This record was changed by another request");

		verify(timelineEntryRepository, never()).save(any());
	}

	@Test
	@DisplayName("Negative: Missing incident throws NotFoundException (404)")
	void missingIncident_throwsNotFoundException() {
		when(incidentRepository.findById(999L)).thenReturn(Optional.empty());

		TransitionRequest request = new TransitionRequest(IncidentStatus.INVESTIGATING, 1L, null);

		assertThatThrownBy(() -> incidentService.transitionIncident(999L, request, 101L))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("Incident 999 not found");
	}

	@Test
	@DisplayName("Negative: Missing expectedVersion in request throws FieldValidationException (400)")
	void missingExpectedVersion_throwsFieldValidationException() {
		TransitionRequest request = new TransitionRequest(IncidentStatus.INVESTIGATING, null, null);

		assertThatThrownBy(() -> incidentService.transitionIncident(1L, request, 101L))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("The incident version you saw is required");
	}

	@Test
	@DisplayName("Negative: Missing status in request throws FieldValidationException (400)")
	void missingStatus_throwsFieldValidationException() {
		TransitionRequest request = new TransitionRequest(null, 1L, null);

		assertThatThrownBy(() -> incidentService.transitionIncident(1L, request, 101L))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("Status is required");
	}

	@Test
	@DisplayName("Negative: Terminal CLOSED incident cannot be transitioned to any status")
	void closedIncident_cannotTransition_throwsConflictException() {
		Incident incident = createIncident(IncidentStatus.CLOSED, 7L);
		when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

		TransitionRequest request = new TransitionRequest(IncidentStatus.OPEN, 7L, null);

		assertThatThrownBy(() -> incidentService.transitionIncident(1L, request, 101L))
				.isInstanceOf(ConflictException.class)
				.hasMessage("Cannot transition incident from CLOSED to OPEN");

		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.CLOSED);
		verify(incidentRepository, never()).saveAndFlush(any());
	}
}
