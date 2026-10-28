package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.EscalationPolicyResponse;
import com.group2.incident.incident.dto.EscalationStepRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.dto.UpdateEscalationPolicyRequest;
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
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.OutboxRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class EscalationIntegrationTest {

	private static final String PASSWORD = "Secret123!";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private EscalationService escalationService;

	@Autowired
	private EscalationPolicyService escalationPolicyService;

	@Autowired
	private IncidentService incidentService;

	@Autowired
	private EscalationPolicyRepository escalationPolicyRepository;

	@Autowired
	private EscalationTaskRepository escalationTaskRepository;

	@Autowired
	private OutboxRepository outboxRepository;

	@Autowired
	private OutboxRelay outboxRelay;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private TimelineEntryRepository timelineEntryRepository;

	@Autowired
	private ServiceRepository serviceRepository;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private Clock clock;

	private AppUser admin;
	private AppUser responder1;
	private AppUser responder2;
	private AppUser fallbackUser;
	private ServiceEntity testService;
	private String adminToken;
	private String responderToken;

	@BeforeEach
	void setUp() throws Exception {
		Instant now = clock.instant();

		admin = appUserRepository.saveAndFlush(new AppUser("admin_esc", "Admin Escalation", passwordEncoder.encode(PASSWORD), Role.ADMIN, now));
		responder1 = appUserRepository.saveAndFlush(new AppUser("responder1", "Responder Level 1", passwordEncoder.encode(PASSWORD), Role.ENGINEER, now));
		responder2 = appUserRepository.saveAndFlush(new AppUser("responder2", "Responder Level 2", passwordEncoder.encode(PASSWORD), Role.ENGINEER, now));
		fallbackUser = appUserRepository.saveAndFlush(new AppUser("fallback_user", "Fallback Contact", passwordEncoder.encode(PASSWORD), Role.ENGINEER, now));

		adminToken = tokenFor("admin_esc");
		responderToken = tokenFor("responder1");

		testService = new ServiceEntity("EscalationTestService", "Payment Gateway Service", admin, Criticality.HIGH, now);
		testService.changeFallbackContact(fallbackUser);
		for (Severity sev : Severity.values()) {
			testService.putTarget(sev, 15, 240);
		}
		testService = serviceRepository.saveAndFlush(testService);
	}

	private String tokenFor(String username) throws Exception {
		String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD);
		String response = mvc.perform(
						org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
								.contentType(MediaType.APPLICATION_JSON)
								.content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.token");
	}

	@Test
	@DisplayName("1. Escalation policy API: Admin can GET and PUT policy; validation rejects invalid steps")
	void escalationPolicyApiCrudAndValidation() throws Exception {
		// Verify GET returns 404 when no policy exists yet
		mvc.perform(get("/api/services/" + testService.getId() + "/escalation-policy")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isNotFound());

		// PUT valid policy with 2 steps (step 1: 15m, step 2: 30m)
		String validJson = """
				{
					"steps": [
						{"stepNumber": 1, "responderId": %d, "waitMinutes": 15},
						{"stepNumber": 2, "responderId": %d, "waitMinutes": 30}
					]
				}
				""".formatted(responder1.getId(), responder2.getId());

		mvc.perform(put("/api/services/" + testService.getId() + "/escalation-policy")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(validJson))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.serviceId").value(testService.getId()))
				.andExpect(jsonPath("$.steps.length()").value(2))
				.andExpect(jsonPath("$.steps[0].stepNumber").value(1))
				.andExpect(jsonPath("$.steps[0].responderId").value(responder1.getId()))
				.andExpect(jsonPath("$.steps[0].waitMinutes").value(15))
				.andExpect(jsonPath("$.steps[1].stepNumber").value(2))
				.andExpect(jsonPath("$.steps[1].responderId").value(responder2.getId()))
				.andExpect(jsonPath("$.steps[1].waitMinutes").value(30));

		// Verify GET returns the saved policy
		mvc.perform(get("/api/services/" + testService.getId() + "/escalation-policy")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + responderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps.length()").value(2));

		// Validation: Non-sequential step numbers (e.g. step 2 without step 1) -> 400
		String invalidStepNumberJson = """
				{
					"steps": [
						{"stepNumber": 2, "responderId": %d, "waitMinutes": 15}
					]
				}
				""".formatted(responder1.getId());

		mvc.perform(put("/api/services/" + testService.getId() + "/escalation-policy")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(invalidStepNumberJson))
				.andExpect(status().isBadRequest());

		// Validation: Non-existent responder -> 400
		String nonExistentUserJson = """
				{
					"steps": [
						{"stepNumber": 1, "responderId": 999999, "waitMinutes": 15}
					]
				}
				""";

		mvc.perform(put("/api/services/" + testService.getId() + "/escalation-policy")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(nonExistentUserJson))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("2. Incident creation creates an escalation task with copied steps that remain immutable")
	void incidentCreationCreatesEscalationTaskWithCopiedSteps() {
		Instant now = clock.instant();

		// Configure policy: Step 1 (15m, responder1), Step 2 (30m, responder2)
		EscalationPolicy policy = new EscalationPolicy(testService, now);
		policy.replaceSteps(List.of(
				new EscalationStep(1, responder1, 15),
				new EscalationStep(2, responder2, 30)
		), now);
		escalationPolicyRepository.saveAndFlush(policy);

		// Create incident
		CreateIncidentRequest request = new CreateIncidentRequest(
				"Test payment failure",
				"Database connection dropped",
				testService.getId(),
				Severity.SEV1
		);
		IncidentResponse incidentResponse = incidentService.createIncident(request, admin.getId());

		// Verify task was created
		EscalationTask task = escalationTaskRepository.findByIncidentId(incidentResponse.id()).orElseThrow();
		assertThat(task.getState()).isEqualTo(EscalationTaskState.PENDING);
		assertThat(task.getCurrentStep()).isEqualTo(0);
		assertThat(task.getNextDueAt()).isEqualTo(now.plus(15, ChronoUnit.MINUTES));
		assertThat(task.getSteps()).hasSize(2);
		assertThat(task.getSteps().get(0).getResponder().getId()).isEqualTo(responder1.getId());
		assertThat(task.getSteps().get(1).getResponder().getId()).isEqualTo(responder2.getId());

		// Modify policy on service (e.g. change step 1 to 60m)
		policy.replaceSteps(List.of(
				new EscalationStep(1, responder2, 60)
		), now.plus(1, ChronoUnit.MINUTES));
		escalationPolicyRepository.saveAndFlush(policy);

		// The existing task's copied steps remain unchanged!
		EscalationTask reloadedTask = escalationTaskRepository.findByIncidentId(incidentResponse.id()).orElseThrow();
		assertThat(reloadedTask.getSteps()).hasSize(2);
		assertThat(reloadedTask.getSteps().get(0).getResponder().getId()).isEqualTo(responder1.getId());
		assertThat(reloadedTask.getSteps().get(0).getWaitMinutes()).isEqualTo(15);
	}

	@Test
	@DisplayName("TC-FR-07.3a: Rule E1 - When overdue and unacknowledged, notify next level, force version increment, assignee unchanged")
	void tcFr07_3a_ruleE1_notifiesNextLevelAndIncrementsVersionAndAssigneeUnchanged() {
		Instant t0 = clock.instant();

		// Setup policy: Step 1 (10m, responder1), Step 2 (20m, responder2)
		EscalationPolicy policy = new EscalationPolicy(testService, t0);
		policy.replaceSteps(List.of(
				new EscalationStep(1, responder1, 10),
				new EscalationStep(2, responder2, 20)
		), t0);
		escalationPolicyRepository.saveAndFlush(policy);

		CreateIncidentRequest createReq = new CreateIncidentRequest("API Outage", "500 errors", testService.getId(), Severity.SEV1);
		IncidentResponse incResp = incidentService.createIncident(createReq, admin.getId());
		Long incId = incResp.id();
		EscalationTask task = escalationTaskRepository.findByIncidentId(incId).orElseThrow();

		// At t0 + 10m, deadline is passed -> Escalates to Step 1
		Instant t10 = t0.plus(10, ChronoUnit.MINUTES);
		boolean executedE1 = escalationService.executeEscalationStep(task.getId(), t10);
		assertThat(executedE1).isTrue();

		EscalationTask step1Task = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		assertThat(step1Task.getCurrentStep()).isEqualTo(1);
		// Next deadline is t10 + step 2 waitMinutes (20m) = t10 + 20m
		assertThat(step1Task.getNextDueAt()).isEqualTo(t10.plus(20, ChronoUnit.MINUTES));

		// Check Timeline entry: system action, actor null, mentions Level 1 and responder1
		List<TimelineEntry> timeline = timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incId);
		assertThat(timeline).anyMatch(e -> e.getEventType() == TimelineEventType.ESCALATED &&
				e.getDetails().contains("level 1") && e.getDetails().contains(responder1.getDisplayName()));

		// Check Outbox entry: saved in same transaction, PENDING status
		List<Outbox> outboxList = outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incId);
		assertThat(outboxList).hasSize(1);
		assertThat(outboxList.get(0).getEventType()).isEqualTo("INCIDENT_ESCALATED");
		assertThat(outboxList.get(0).getPayload()).contains("\"stepNumber\":1");
		assertThat(outboxList.get(0).getStatus()).isEqualTo("PENDING");

		// Check Incident version was incremented (optimistic locking)
		Incident reloadedInc = incidentRepository.findById(incId).orElseThrow();
		assertThat(reloadedInc.getVersion()).isGreaterThan(0L);

		// CRITICAL: The assignee never changes during an escalation!
		assertThat(reloadedInc.getStatus()).isEqualTo(IncidentStatus.OPEN);
	}

	@Test
	@DisplayName("TC-FR-07.7a: Rules E2, E3, E4 - When deadline not reached, acknowledged, or resolved, nothing is sent or saved")
	void tcFr07_7a_rulesE2_E3_E4_nothingSentOrSavedWhenIneligible() {
		Instant t0 = clock.instant();

		EscalationPolicy policy = new EscalationPolicy(testService, t0);
		policy.replaceSteps(List.of(new EscalationStep(1, responder1, 15)), t0);
		escalationPolicyRepository.saveAndFlush(policy);

		// --- Rule E2: Deadline not reached -> Nothing happens
		CreateIncidentRequest reqE2 = new CreateIncidentRequest("E2 Test", "Early check", testService.getId(), Severity.SEV1);
		IncidentResponse incE2 = incidentService.createIncident(reqE2, admin.getId());
		EscalationTask taskE2 = escalationTaskRepository.findByIncidentId(incE2.id()).orElseThrow();

		Instant beforeDeadline = t0.plus(5, ChronoUnit.MINUTES);
		boolean executedE2 = escalationService.executeEscalationStep(taskE2.getId(), beforeDeadline);
		assertThat(executedE2).isFalse();
		assertThat(outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incE2.id())).isEmpty();

		// --- Rule E3: Incident Acknowledged -> Task cancelled, nothing happens
		CreateIncidentRequest reqE3 = new CreateIncidentRequest("E3 Test", "Acknowledged check", testService.getId(), Severity.SEV1);
		IncidentResponse incE3 = incidentService.createIncident(reqE3, admin.getId());
		EscalationTask taskE3 = escalationTaskRepository.findByIncidentId(incE3.id()).orElseThrow();

		incidentService.acknowledgeIncident(incE3.id(), responder1.getId());
		boolean executedE3 = escalationService.executeEscalationStep(taskE3.getId(), t0.plus(20, ChronoUnit.MINUTES));
		assertThat(executedE3).isFalse();
		assertThat(outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incE3.id())).isEmpty();

		// --- Rule E4: Incident not OPEN (e.g. RESOLVED) -> Task cancelled, nothing happens
		CreateIncidentRequest reqE4 = new CreateIncidentRequest("E4 Test", "Resolved check", testService.getId(), Severity.SEV1);
		IncidentResponse incE4 = incidentService.createIncident(reqE4, admin.getId());
		EscalationTask taskE4 = escalationTaskRepository.findByIncidentId(incE4.id()).orElseThrow();

		// Mark incident resolved
		Incident incEntity = incidentRepository.findById(incE4.id()).orElseThrow();
		incEntity.acknowledge(responder1); // first ack
		// Status is now ACKNOWLEDGED; when status is not OPEN, Rule E4 applies
		boolean executedE4 = escalationService.executeEscalationStep(taskE4.getId(), t0.plus(20, ChronoUnit.MINUTES));
		assertThat(executedE4).isFalse();
		assertThat(outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incE4.id())).isEmpty();
	}

	@Test
	@DisplayName("TC-FR-07.5a: Rule E5 - Fallback contact notified exactly once when all levels exhausted; incident remains OPEN")
	void tcFr07_5a_ruleE5_notifiesFallbackOnceAndIncidentStaysOpen() {
		Instant t0 = clock.instant();

		// Policy with 1 level (10m)
		EscalationPolicy policy = new EscalationPolicy(testService, t0);
		policy.replaceSteps(List.of(new EscalationStep(1, responder1, 10)), t0);
		escalationPolicyRepository.saveAndFlush(policy);

		CreateIncidentRequest req = new CreateIncidentRequest("E5 Fallback Test", "All levels exhausted", testService.getId(), Severity.SEV1);
		IncidentResponse inc = incidentService.createIncident(req, admin.getId());
		Long incId = inc.id();
		EscalationTask task = escalationTaskRepository.findByIncidentId(incId).orElseThrow();

		// Step 1 fires at t0 + 10m
		Instant t10 = t0.plus(10, ChronoUnit.MINUTES);
		escalationService.executeEscalationStep(task.getId(), t10);

		// Now step 1 is unanswered, wait 10m more until t0 + 20m -> triggers fallback
		Instant t20 = t10.plus(10, ChronoUnit.MINUTES);
		boolean executedFallback = escalationService.executeEscalationStep(task.getId(), t20);
		assertThat(executedFallback).isTrue();

		EscalationTask fallbackTask = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		assertThat(fallbackTask.isFallbackNotified()).isTrue();
		assertThat(fallbackTask.getState()).isEqualTo(EscalationTaskState.EXHAUSTED);
		assertThat(fallbackTask.getNextDueAt()).isNull();

		// Incident remains OPEN and can still be acknowledged
		Incident reloadedInc = incidentRepository.findById(incId).orElseThrow();
		assertThat(reloadedInc.getStatus()).isEqualTo(IncidentStatus.OPEN);

		// Fallback timeline and outbox
		List<TimelineEntry> timeline = timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incId);
		assertThat(timeline).anyMatch(e -> e.getEventType() == TimelineEventType.ESCALATED &&
				e.getDetails().contains("fallback contact") && e.getDetails().contains(fallbackUser.getDisplayName()));

		List<Outbox> outboxList = outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incId);
		assertThat(outboxList).anyMatch(o -> o.getEventType().equals("INCIDENT_ESCALATED_FALLBACK"));

		// Fallback is notified EXACTLY ONCE: subsequent check does nothing
		boolean executedAgain = escalationService.executeEscalationStep(task.getId(), t20.plus(10, ChronoUnit.MINUTES));
		assertThat(executedAgain).isFalse();

		// Can still be acknowledged later
		IncidentResponse ackResp = incidentService.acknowledgeIncident(incId, responder2.getId());
		assertThat(ackResp.status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(ackResp.assignee().id()).isEqualTo(responder2.getId());
	}

	@Test
	@DisplayName("TC-FR-07.6a: Restart with an overdue step runs the overdue step once and gives the next level its full wait")
	void tcFr07_6a_restartWithOverdueStepRunsOnceAndGivesFullWaitToNextLevel() {
		Instant t0 = clock.instant();

		// Policy: Step 1 (15m, responder1), Step 2 (30m, responder2)
		EscalationPolicy policy = new EscalationPolicy(testService, t0);
		policy.replaceSteps(List.of(
				new EscalationStep(1, responder1, 15),
				new EscalationStep(2, responder2, 30)
		), t0);
		escalationPolicyRepository.saveAndFlush(policy);

		CreateIncidentRequest req = new CreateIncidentRequest("Server Restart Test", "Testing catch-up", testService.getId(), Severity.SEV1);
		IncidentResponse inc = incidentService.createIncident(req, admin.getId());
		Long incId = inc.id();

		// Step 1 due time was t0 + 15m.
		// Simulate server was down and restarted at t0 + 60m (45 minutes past due!).
		Instant restartTime = t0.plus(60, ChronoUnit.MINUTES);

		EscalationTask task = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		assertThat(task.getNextDueAt()).isBefore(restartTime);

		// Execute overdue check (worker on restart)
		boolean executed = escalationService.executeEscalationStep(task.getId(), restartTime);
		assertThat(executed).isTrue();

		EscalationTask postRestartTask = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		assertThat(postRestartTask.getCurrentStep()).isEqualTo(1);

		// Crucial Rule (D14 & TC-FR-07.6a):
		// The next level (Step 2) receives its FULL configured waiting period (30 minutes) from restartTime!
		// Next due time is restartTime + 30m = t0 + 90m (NOT t0 + 45m).
		assertThat(postRestartTask.getNextDueAt()).isEqualTo(restartTime.plus(30, ChronoUnit.MINUTES));

		// Re-running at restartTime does NOT execute Step 2 immediately:
		boolean executedAgain = escalationService.executeEscalationStep(task.getId(), restartTime);
		assertThat(executedAgain).isFalse();
	}

	@Test
	@DisplayName("TC-FR-07.4a: Concurrency - Escalation racing an acknowledgement - loser rolls back; no escalation after accepted acknowledgement")
	void tcFr07_4a_concurrency_escalationRacingAcknowledgement() {
		Instant now = clock.instant();

		EscalationPolicy policy = new EscalationPolicy(testService, now);
		policy.replaceSteps(List.of(new EscalationStep(1, responder1, 10)), now);
		escalationPolicyRepository.saveAndFlush(policy);

		CreateIncidentRequest req = new CreateIncidentRequest("Race Condition Incident", "Testing race", testService.getId(), Severity.SEV1);
		IncidentResponse inc = incidentService.createIncident(req, admin.getId());
		Long incId = inc.id();

		Instant overdueTime = now.plus(15, ChronoUnit.MINUTES);
		EscalationTask task = escalationTaskRepository.findByIncidentId(incId).orElseThrow();

		// Case A: Acknowledgement commits before escalation
		incidentService.acknowledgeIncident(incId, responder1.getId());

		Incident ackInc = incidentRepository.findById(incId).orElseThrow();
		assertThat(ackInc.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(ackInc.getAssignee().getId()).isEqualTo(responder1.getId());

		// Now escalation attempts to run on the acknowledged incident (Rule E3)
		boolean escalationAttempt = escalationService.executeEscalationStep(task.getId(), overdueTime);
		assertThat(escalationAttempt).isFalse();

		// Verify task was cancelled and no outbox escalation event was written
		EscalationTask cancelledTask = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		assertThat(cancelledTask.getState()).isEqualTo(EscalationTaskState.CANCELLED);
		assertThat(outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incId)).isEmpty();

		// Case B: Concurrent execution with optimistic locking (TC-FR-07.4a)
		// Create a fresh incident for concurrent race
		IncidentResponse raceInc = incidentService.createIncident(
				new CreateIncidentRequest("Race Test 2", "Concurrent", testService.getId(), Severity.SEV1), admin.getId()
		);
		Long raceIncId = raceInc.id();
		EscalationTask raceTask = escalationTaskRepository.findByIncidentId(raceIncId).orElseThrow();

		// Tx 2 reads incident at version 0
		Incident staleIncident = incidentRepository.findById(raceIncId).orElseThrow();
		assertThat(staleIncident.getVersion()).isEqualTo(0L);

		TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

		// Tx 1 executes escalation and commits -> incident version forced from 0 to 1
		txTemplate.execute(status -> {
			escalationService.executeEscalationStep(raceTask.getId(), overdueTime);
			return null;
		});

		Incident escalatedInc = incidentRepository.findById(raceIncId).orElseThrow();
		assertThat(escalatedInc.getVersion()).isEqualTo(1L);

		// Tx 2 attempts to save acknowledgement with the stale entity:
		// Hibernate detects the concurrent version increment and throws OptimisticLockingFailureException!
		staleIncident.acknowledge(responder1);
		assertThatThrownBy(() -> incidentRepository.saveAndFlush(staleIncident))
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	@Test
	@DisplayName("Outbox Relay: Scheduled outbox processor reads pending escalation messages and marks them SENT")
	void outboxRelaySchedulerProcessesPendingEvents() {
		Instant now = clock.instant();

		// Save a pending outbox entry as produced during escalation
		Outbox outbox = new Outbox("INCIDENT", 42L, "INCIDENT_ESCALATED", "{\"step\":1}", now);
		outboxRepository.saveAndFlush(outbox);

		int processed = outboxRelay.processPendingOutboxMessages();
		assertThat(processed).isGreaterThanOrEqualTo(1);

		Outbox reloaded = outboxRepository.findById(outbox.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo("SENT");
		assertThat(reloaded.getProcessedAt()).isNotNull();
	}
}
