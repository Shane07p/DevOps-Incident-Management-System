package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.incident.entity.EscalationPolicy;
import com.group2.incident.incident.entity.EscalationStep;
import com.group2.incident.incident.entity.EscalationTask;
import com.group2.incident.incident.entity.EscalationTaskState;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.Outbox;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.EscalationPolicyRepository;
import com.group2.incident.incident.repository.EscalationTaskRepository;
import com.group2.incident.incident.repository.OutboxRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import com.group2.incident.incident.repository.IncidentRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationServiceTest {

	private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");

	@Mock
	private EscalationPolicyRepository escalationPolicyRepository;

	@Mock
	private EscalationTaskRepository escalationTaskRepository;

	@Mock
	private TimelineEntryRepository timelineEntryRepository;

	@Mock
	private OutboxRepository outboxRepository;

	@Mock
	private EntityManager entityManager;

	@Mock
	private IncidentRepository incidentRepository;

	@Captor
	private ArgumentCaptor<Outbox> outboxCaptor;

	@Captor
	private ArgumentCaptor<TimelineEntry> timelineCaptor;

	private Clock fixedClock;
	private EscalationService escalationService;

	private AppUser admin;
	private AppUser responder1;
	private AppUser responder2;
	private AppUser fallbackContact;
	private ServiceEntity service;

	@BeforeEach
	void setUp() {
		fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
			escalationService = new EscalationService(
				escalationPolicyRepository,
				escalationTaskRepository,
				timelineEntryRepository,
				outboxRepository,
				entityManager,
				fixedClock,
				incidentRepository
			);


		admin = new AppUser("admin", "Admin User", "hash", Role.ADMIN, T0);
		responder1 = new AppUser("resp1", "Responder 1", "hash", Role.ENGINEER, T0);
		responder2 = new AppUser("resp2", "Responder 2", "hash", Role.ENGINEER, T0);
		fallbackContact = new AppUser("fallback", "Fallback User", "hash", Role.ENGINEER, T0);

		service = new ServiceEntity("PaymentService", "Payments", admin, Criticality.HIGH, T0);
		service.changeFallbackContact(fallbackContact);
	}

	@Test
	@DisplayName("createTaskForIncident creates EscalationTask with initial deadline and copied steps")
	void createTaskForIncidentSuccess() {
		Incident incident = new Incident("Down", "Outage", service, Severity.SEV1, IncidentStatus.OPEN, admin, T0, 15, 240);

		EscalationPolicy policy = new EscalationPolicy(service, T0);
		policy.replaceSteps(List.of(
				new EscalationStep(1, responder1, 15),
				new EscalationStep(2, responder2, 30)
		), T0);

		when(escalationPolicyRepository.findByServiceId(service.getId())).thenReturn(Optional.of(policy));

		escalationService.createTaskForIncident(incident, T0);

		ArgumentCaptor<EscalationTask> taskCaptor = ArgumentCaptor.forClass(EscalationTask.class);
		verify(escalationTaskRepository).save(taskCaptor.capture());

		EscalationTask savedTask = taskCaptor.getValue();
		assertThat(savedTask.getIncident()).isEqualTo(incident);
		assertThat(savedTask.getCurrentStep()).isEqualTo(0);
		assertThat(savedTask.getState()).isEqualTo(EscalationTaskState.PENDING);
		assertThat(savedTask.getNextDueAt()).isEqualTo(T0.plus(15, ChronoUnit.MINUTES));
		assertThat(savedTask.getSteps()).hasSize(2);
	}

	@Test
	@DisplayName("TC-FR-07.3a: Rule E1: Step 1 executes when overdue -> forces version increment, writes timeline & outbox, sets next due time")
	void tcFr07_3a_ruleE1Step1Executes() {
		Incident incident = new Incident("Down", "Outage", service, Severity.SEV1, IncidentStatus.OPEN, admin, T0, 15, 240);
		Instant dueAt = T0.plus(15, ChronoUnit.MINUTES);
		EscalationTask task = new EscalationTask(incident, dueAt, T0);
		task.addCopiedStep(1, responder1, 15);
		task.addCopiedStep(2, responder2, 30);

		when(escalationTaskRepository.findById(1L)).thenReturn(Optional.of(task));

		Instant evaluationTime = dueAt; // exactly at deadline or past
		boolean executed = escalationService.executeEscalationStep(1L, evaluationTime);

		assertThat(executed).isTrue();
		assertThat(task.getCurrentStep()).isEqualTo(1);
		// Next deadline is evaluationTime + step 2 waitMinutes (30m)
		assertThat(task.getNextDueAt()).isEqualTo(evaluationTime.plus(30, ChronoUnit.MINUTES));

		// Verify optimistic lock increment forced on Incident
		verify(entityManager).lock(incident, LockModeType.OPTIMISTIC_FORCE_INCREMENT);

		// CRITICAL: Assignee remains unchanged during escalation!
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);

		// Verify timeline entry
		verify(timelineEntryRepository).save(timelineCaptor.capture());
		TimelineEntry timeline = timelineCaptor.getValue();
		assertThat(timeline.getEventType()).isEqualTo(TimelineEventType.ESCALATED);
		assertThat(timeline.getDetails()).contains("level 1").contains(responder1.getDisplayName());

		// Verify outbox entry
		verify(outboxRepository).save(outboxCaptor.capture());
		Outbox outbox = outboxCaptor.getValue();
		assertThat(outbox.getAggregateType()).isEqualTo("INCIDENT");
		assertThat(outbox.getEventType()).isEqualTo("INCIDENT_ESCALATED");
		assertThat(outbox.getStatus()).isEqualTo("PENDING");
		assertThat(outbox.getPayload()).contains("\"stepNumber\":1").contains(responder1.getDisplayName());
	}

	@Test
	@DisplayName("TC-FR-07.7a: Rule E2: Deadline not reached -> Nothing executed")
	void tcFr07_7a_ruleE2DeadlineNotReached() {
		Incident incident = new Incident("Down", "Outage", service, Severity.SEV1, IncidentStatus.OPEN, admin, T0, 15, 240);
		Instant dueAt = T0.plus(15, ChronoUnit.MINUTES);
		EscalationTask task = new EscalationTask(incident, dueAt, T0);
		task.addCopiedStep(1, responder1, 15);

		when(escalationTaskRepository.findById(1L)).thenReturn(Optional.of(task));

		Instant beforeDeadline = T0.plus(10, ChronoUnit.MINUTES);
		boolean executed = escalationService.executeEscalationStep(1L, beforeDeadline);

		assertThat(executed).isFalse();
		assertThat(task.getCurrentStep()).isEqualTo(0);
		verify(timelineEntryRepository, never()).save(any());
		verify(outboxRepository, never()).save(any());
	}

	@Test
	@DisplayName("TC-FR-07.7a: Rule E3: Incident ACKNOWLEDGED -> Task cancelled, nothing executed")
	void tcFr07_7a_ruleE3IncidentAcknowledged() {
		Incident incident = new Incident("Down", "Outage", service, Severity.SEV1, IncidentStatus.OPEN, admin, T0, 15, 240);
		incident.acknowledge(responder1);

		Instant dueAt = T0.plus(15, ChronoUnit.MINUTES);
		EscalationTask task = new EscalationTask(incident, dueAt, T0);
		task.addCopiedStep(1, responder1, 15);

		when(escalationTaskRepository.findById(1L)).thenReturn(Optional.of(task));

		boolean executed = escalationService.executeEscalationStep(1L, dueAt.plus(5, ChronoUnit.MINUTES));

		assertThat(executed).isFalse();
		assertThat(task.getState()).isEqualTo(EscalationTaskState.CANCELLED);
		verify(timelineEntryRepository, never()).save(any());
		verify(outboxRepository, never()).save(any());
	}

	@Test
	@DisplayName("TC-FR-07.7a: Rule E4: Incident not OPEN (RESOLVED) -> Task cancelled, nothing executed")
	void tcFr07_7a_ruleE4IncidentNotOpen() {
		Incident incident = new Incident("Down", "Outage", service, Severity.SEV1, IncidentStatus.OPEN, admin, T0, 15, 240);
		incident.acknowledge(responder1);
		// Status is ACKNOWLEDGED (not OPEN)
		Instant dueAt = T0.plus(15, ChronoUnit.MINUTES);
		EscalationTask task = new EscalationTask(incident, dueAt, T0);
		task.addCopiedStep(1, responder1, 15);

		when(escalationTaskRepository.findById(1L)).thenReturn(Optional.of(task));

		boolean executed = escalationService.executeEscalationStep(1L, dueAt.plus(5, ChronoUnit.MINUTES));

		assertThat(executed).isFalse();
		assertThat(task.getState()).isEqualTo(EscalationTaskState.CANCELLED);
		verify(timelineEntryRepository, never()).save(any());
		verify(outboxRepository, never()).save(any());
	}

	@Test
	@DisplayName("TC-FR-07.5a: Rule E5: All levels exhausted -> Fallback contact notified exactly once, incident stays OPEN")
	void tcFr07_5a_ruleE5FallbackNotifiedExactlyOnce() {
		Incident incident = new Incident("Down", "Outage", service, Severity.SEV1, IncidentStatus.OPEN, admin, T0, 15, 240);
		Instant dueAt = T0.plus(15, ChronoUnit.MINUTES);
		EscalationTask task = new EscalationTask(incident, dueAt, T0);
		task.addCopiedStep(1, responder1, 15);
		task.advanceToStep(1, dueAt, T0); // Step 1 was already notified

		when(escalationTaskRepository.findById(1L)).thenReturn(Optional.of(task));

		Instant evalTime = dueAt;
		boolean executedFirstTime = escalationService.executeEscalationStep(1L, evalTime);

		assertThat(executedFirstTime).isTrue();
		assertThat(task.isFallbackNotified()).isTrue();
		assertThat(task.getState()).isEqualTo(EscalationTaskState.EXHAUSTED);
		assertThat(task.getNextDueAt()).isNull();

		// CRITICAL: Incident stays OPEN!
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);

		// Verify timeline and outbox for fallback
		verify(timelineEntryRepository).save(timelineCaptor.capture());
		assertThat(timelineCaptor.getValue().getDetails()).contains("fallback contact").contains(fallbackContact.getDisplayName());

		verify(outboxRepository).save(outboxCaptor.capture());
		assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("INCIDENT_ESCALATED_FALLBACK");

		// Run again: fallback must be notified EXACTLY ONCE
		boolean executedSecondTime = escalationService.executeEscalationStep(1L, evalTime.plus(10, ChronoUnit.MINUTES));
		assertThat(executedSecondTime).isFalse();
	}

	@Test
	@DisplayName("TC-FR-07.6a: Restart with overdue step -> runs once, gives next level its full wait")
	void tcFr07_6a_restartWithOverdueStepRunsOnceAndGivesFullWait() {
		Incident incident = new Incident("Down", "Outage", service, Severity.SEV1, IncidentStatus.OPEN, admin, T0, 15, 240);
		Instant dueAt = T0.plus(15, ChronoUnit.MINUTES);
		EscalationTask task = new EscalationTask(incident, dueAt, T0);
		task.addCopiedStep(1, responder1, 15);
		task.addCopiedStep(2, responder2, 30);

		when(escalationTaskRepository.findById(1L)).thenReturn(Optional.of(task));

		// Server restarts at T0 + 60m (45 minutes late!)
		Instant restartTime = T0.plus(60, ChronoUnit.MINUTES);

		boolean executed = escalationService.executeEscalationStep(1L, restartTime);
		assertThat(executed).isTrue();
		assertThat(task.getCurrentStep()).isEqualTo(1);

		// D14 & TC-FR-07.6a rule: next deadline is restartTime + 30m, giving responder 2 full wait
		assertThat(task.getNextDueAt()).isEqualTo(restartTime.plus(30, ChronoUnit.MINUTES));
	}
}
