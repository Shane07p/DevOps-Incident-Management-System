package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.entity.EscalationPolicy;
import com.group2.incident.incident.entity.EscalationStep;
import com.group2.incident.incident.entity.EscalationTask;
import com.group2.incident.incident.entity.EscalationTaskState;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.Outbox;
import com.group2.incident.incident.entity.OutboxStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.EscalationPolicyRepository;
import com.group2.incident.incident.repository.EscalationTaskRepository;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.OutboxRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
	private EscalationWorker escalationWorker;

	@Autowired
	private EntityManager entityManager;

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
	private JdbcTemplate jdbc;

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

	// Most tests roll back with the test transaction. The two outside it commit, so delete just what
	// this class saved there, children first.
	@AfterEach
	void deleteCommittedRows() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return;
		}
		Long serviceId = testService.getId();
		Object[] userIds = { admin.getId(), responder1.getId(), responder2.getId(), fallbackUser.getId() };
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			// Replica mode skips the append-only triggers, but also foreign keys and cascades, so it
			// covers only these two deletes.
			jdbc.execute("SET LOCAL session_replication_role = 'replica'");
			jdbc.update("DELETE FROM timeline_entry WHERE incident_id IN (SELECT id FROM incident WHERE service_id = ?)", serviceId);
			jdbc.update("DELETE FROM audit_entry WHERE actor_id IN (?, ?, ?, ?)", userIds);
			jdbc.execute("SET LOCAL session_replication_role = 'origin'");
			jdbc.update("DELETE FROM outbox WHERE aggregate_type = 'INCIDENT' AND aggregate_id IN (SELECT id FROM incident WHERE service_id = ?)", serviceId);
			// Escalation tasks go with their incident; the policy and shifts go with the service.
			jdbc.update("DELETE FROM incident WHERE service_id = ?", serviceId);
			jdbc.update("DELETE FROM service_sla_target WHERE service_id = ?", serviceId);
			jdbc.update("DELETE FROM service WHERE id = ?", serviceId);
			jdbc.update("DELETE FROM app_user WHERE id IN (?, ?, ?, ?)", userIds);
		});
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

		// Update existing policy (tests in-place step replacement without duplicate key constraint violation)
		String updateExistingJson = """
				{
					"steps": [
						{"stepNumber": 1, "responderId": %d, "waitMinutes": 20}
					]
				}
				""".formatted(responder2.getId());

		mvc.perform(put("/api/services/" + testService.getId() + "/escalation-policy")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateExistingJson))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps.length()").value(1))
				.andExpect(jsonPath("$.steps[0].responderId").value(responder2.getId()))
				.andExpect(jsonPath("$.steps[0].waitMinutes").value(20));

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

		// Validation: Two steps numbered 1 -> 400
		String duplicateStepNumberJson = """
				{
					"steps": [
						{"stepNumber": 1, "responderId": %d, "waitMinutes": 15},
						{"stepNumber": 1, "responderId": %d, "waitMinutes": 30}
					]
				}
				""".formatted(responder1.getId(), responder2.getId());

		mvc.perform(put("/api/services/" + testService.getId() + "/escalation-policy")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(duplicateStepNumberJson))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.steps").value("Step numbers must be sequential starting at 1; expected 2 but got 1"));

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
		assertThat(task.getNextDueAt().truncatedTo(ChronoUnit.MICROS))
				.isEqualTo(incidentResponse.createdAt().plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS));
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
		Long assigneeBefore = incResp.assignee() == null ? null : incResp.assignee().id();
		EscalationTask task = escalationTaskRepository.findByIncidentId(incId).orElseThrow();

		// At task.getNextDueAt(), deadline is reached -> Escalates to Step 1
		Instant evaluationTime = task.getNextDueAt();
		boolean executedE1 = escalationService.executeEscalationStep(task.getId(), evaluationTime);
		assertThat(executedE1).isTrue();

		EscalationTask step1Task = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		assertThat(step1Task.getCurrentStep()).isEqualTo(1);
		// Next deadline is evaluationTime + step 2 waitMinutes (20m)
		assertThat(step1Task.getNextDueAt().truncatedTo(ChronoUnit.MICROS))
				.isEqualTo(evaluationTime.plus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS));

		// Check Timeline entry: system action, actor null, mentions Level 1 and responder1
		List<TimelineEntry> timeline = timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incId);
		assertThat(timeline).anyMatch(e -> e.getEventType() == TimelineEventType.ESCALATED &&
				e.getDetails().contains("level 1") && e.getDetails().contains(responder1.getDisplayName()));

		// Check Outbox entry: saved in same transaction, PENDING status
		List<Outbox> outboxList = outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incId);
		assertThat(outboxList).hasSize(1);
		assertThat(outboxList.get(0).getEventType()).isEqualTo("INCIDENT_ESCALATED");
		assertThat(outboxList.get(0).getPayload()).contains("\"stepNumber\":1");
		assertThat(outboxList.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);

		// Escalation notifies; it never changes the assignee or the status. The version increment lands
		// at commit, so it is checked in TC-FR-07.4a and workerEscalatesOverdueTaskAndCommits.
		Incident reloadedInc = incidentRepository.findById(incId).orElseThrow();
		assertThat(reloadedInc.getAssignee() == null ? null : reloadedInc.getAssignee().getId()).isEqualTo(assigneeBefore);
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

		Instant beforeDeadline = taskE2.getNextDueAt().minus(1, ChronoUnit.MINUTES);
		boolean executedE2 = escalationService.executeEscalationStep(taskE2.getId(), beforeDeadline);
		assertThat(executedE2).isFalse();
		assertThat(outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incE2.id())).isEmpty();

		// --- Rule E3: Incident Acknowledged -> Task cancelled, nothing happens
		CreateIncidentRequest reqE3 = new CreateIncidentRequest("E3 Test", "Acknowledged check", testService.getId(), Severity.SEV1);
		IncidentResponse incE3 = incidentService.createIncident(reqE3, admin.getId());
		EscalationTask taskE3 = escalationTaskRepository.findByIncidentId(incE3.id()).orElseThrow();

		setStatus(incE3.id(), IncidentStatus.ACKNOWLEDGED);
		escalationService.cancelTaskForIncident(incE3.id(), t0.plus(5, ChronoUnit.MINUTES));

		// Task was cancelled upon acknowledgement and its deadline cleared
		EscalationTask cancelledTaskE3 = escalationTaskRepository.findByIncidentId(incE3.id()).orElseThrow();
		assertThat(cancelledTaskE3.getState()).isEqualTo(EscalationTaskState.CANCELLED);
		assertThat(cancelledTaskE3.getNextDueAt()).isNull();

		boolean executedE3 = escalationService.executeEscalationStep(taskE3.getId(), t0.plus(20, ChronoUnit.MINUTES));
		assertThat(executedE3).isFalse();
		assertThat(outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incE3.id())).isEmpty();

		// --- Rule E4: Incident not OPEN (e.g. RESOLVED) -> Task cancelled, nothing happens
		CreateIncidentRequest reqE4 = new CreateIncidentRequest("E4 Test", "Resolved check", testService.getId(), Severity.SEV1);
		IncidentResponse incE4 = incidentService.createIncident(reqE4, admin.getId());
		EscalationTask taskE4 = escalationTaskRepository.findByIncidentId(incE4.id()).orElseThrow();

		setStatus(incE4.id(), IncidentStatus.RESOLVED);

		boolean executedE4 = escalationService.executeEscalationStep(taskE4.getId(), t0.plus(20, ChronoUnit.MINUTES));
		assertThat(executedE4).isFalse();
		assertThat(outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incE4.id())).isEmpty();

		EscalationTask cancelledTaskE4 = escalationTaskRepository.findByIncidentId(incE4.id()).orElseThrow();
		assertThat(cancelledTaskE4.getState()).isEqualTo(EscalationTaskState.CANCELLED);
		assertThat(cancelledTaskE4.getNextDueAt()).isNull();
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

		// Step 1 fires at task.getNextDueAt()
		Instant tStep1 = task.getNextDueAt();
		boolean executedStep1 = escalationService.executeEscalationStep(task.getId(), tStep1);
		assertThat(executedStep1).isTrue();

		// Reload task: Step 1 is active, and next due date is set for fallback evaluation
		EscalationTask step1Task = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		Instant tFallback = step1Task.getNextDueAt();
		assertThat(tFallback).isNotNull();

		// Now step 1 is unanswered, wait until tFallback -> triggers fallback
		boolean executedFallback = escalationService.executeEscalationStep(task.getId(), tFallback);
		assertThat(executedFallback).isTrue();

		EscalationTask fallbackTask = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		assertThat(fallbackTask.isFallbackNotified()).isTrue();
		assertThat(fallbackTask.getState()).isEqualTo(EscalationTaskState.EXHAUSTED);
		assertThat(fallbackTask.getNextDueAt()).isNull();

		// Incident remains OPEN, so it can still be acknowledged (#83)
		Incident reloadedInc = incidentRepository.findById(incId).orElseThrow();
		assertThat(reloadedInc.getStatus()).isEqualTo(IncidentStatus.OPEN);

		// Fallback timeline and outbox
		List<TimelineEntry> timeline = timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incId);
		assertThat(timeline).anyMatch(e -> e.getEventType() == TimelineEventType.ESCALATED &&
				e.getDetails().contains("fallback contact") && e.getDetails().contains(fallbackUser.getDisplayName()));

		List<Outbox> outboxList = outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incId);
		assertThat(outboxList).anyMatch(o -> o.getEventType().equals("INCIDENT_ESCALATED_FALLBACK"));

		// Fallback is notified EXACTLY ONCE: subsequent check does nothing
		boolean executedAgain = escalationService.executeEscalationStep(task.getId(), tFallback.plus(10, ChronoUnit.MINUTES));
		assertThat(executedAgain).isFalse();
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

		EscalationTask task = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		// Simulate server was down and restarted 45 minutes past step 1 due date
		Instant restartTime = task.getNextDueAt().plus(45, ChronoUnit.MINUTES);
		assertThat(task.getNextDueAt()).isBefore(restartTime);

		// Execute overdue check (worker on restart)
		boolean executed = escalationService.executeEscalationStep(task.getId(), restartTime);
		assertThat(executed).isTrue();

		EscalationTask postRestartTask = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		assertThat(postRestartTask.getCurrentStep()).isEqualTo(1);

		// Crucial Rule (D14 & TC-FR-07.6a):
		// The next level (Step 2) receives its FULL configured waiting period (30 minutes) from restartTime!
		// Next due time is restartTime + 30m.
		assertThat(postRestartTask.getNextDueAt().truncatedTo(ChronoUnit.MICROS))
				.isEqualTo(restartTime.plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS));

		// Re-running at restartTime does NOT execute Step 2 immediately:
		boolean executedAgain = escalationService.executeEscalationStep(task.getId(), restartTime);
		assertThat(executedAgain).isFalse();
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("TC-FR-07.4a: Concurrency - Escalation racing an acknowledgement - loser rolls back; no escalation after accepted acknowledgement")
	void tcFr07_4a_concurrency_escalationRacingAcknowledgement() {
		Instant now = clock.instant();

		TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

		// Setup policy in its own committed transaction
		txTemplate.execute(status -> {
			EscalationPolicy policy = escalationPolicyRepository.findByServiceId(testService.getId())
					.orElseGet(() -> new EscalationPolicy(testService, now));
			policy.replaceSteps(List.of(new EscalationStep(1, responder1, 10)), now);
			escalationPolicyRepository.saveAndFlush(policy);
			return null;
		});

		// Case A: Acknowledgement commits before escalation
		IncidentResponse inc = incidentService.createIncident(
				new CreateIncidentRequest("Race Condition Incident 1", "Testing race 1", testService.getId(), Severity.SEV1),
				admin.getId()
		);
		Long incId = inc.id();
		EscalationTask task = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		Instant overdueTime = task.getNextDueAt().plus(5, ChronoUnit.MINUTES);

		setStatus(incId, IncidentStatus.ACKNOWLEDGED);

		// Now escalation attempts to run on the acknowledged incident (Rule E3)
		boolean escalationAttempt = escalationService.executeEscalationStep(task.getId(), overdueTime);
		assertThat(escalationAttempt).isFalse();

		// Verify task was cancelled and no outbox escalation event was written
		EscalationTask cancelledTask = escalationTaskRepository.findByIncidentId(incId).orElseThrow();
		assertThat(cancelledTask.getState()).isEqualTo(EscalationTaskState.CANCELLED);
		assertThat(cancelledTask.getNextDueAt()).isNull();
		assertThat(outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", incId)).isEmpty();

		// Case B: Concurrent execution with optimistic locking (TC-FR-07.4a)
		IncidentResponse raceInc = incidentService.createIncident(
				new CreateIncidentRequest("Race Test 2", "Concurrent", testService.getId(), Severity.SEV1),
				admin.getId()
		);
		Long raceIncId = raceInc.id();
		EscalationTask raceTask = escalationTaskRepository.findByIncidentId(raceIncId).orElseThrow();
		Instant raceOverdueTime = raceTask.getNextDueAt().plus(5, ChronoUnit.MINUTES);

		// Tx 2 reads incident at version 0
		Incident staleIncident = incidentRepository.findById(raceIncId).orElseThrow();
		assertThat(staleIncident.getVersion()).isEqualTo(0L);

		// Tx 1 executes escalation and commits -> incident version forced from 0 to 1
		boolean escalated = escalationService.escalateTaskInNewTransaction(raceTask.getId(), raceOverdueTime);
		assertThat(escalated).isTrue();

		Incident escalatedInc = incidentRepository.findById(raceIncId).orElseThrow();
		assertThat(escalatedInc.getVersion()).isEqualTo(1L);

		// Tx 2 saves a change (as an acknowledgement would) from the stale entity:
		// Hibernate detects the concurrent version increment and throws OptimisticLockingFailureException!
		staleIncident.assignTo(responder1);
		assertThatThrownBy(() -> incidentRepository.saveAndFlush(staleIncident))
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("The worker escalates an overdue task in its own transaction, which commits the version increment")
	void workerEscalatesOverdueTaskAndCommits() {
		Instant now = clock.instant();
		new TransactionTemplate(transactionManager).execute(status -> {
			EscalationPolicy policy = new EscalationPolicy(testService, now);
			policy.replaceSteps(List.of(new EscalationStep(1, responder1, 10)), now);
			escalationPolicyRepository.saveAndFlush(policy);
			return null;
		});
		IncidentResponse inc = incidentService.createIncident(
				new CreateIncidentRequest("Worker Test", "Overdue", testService.getId(), Severity.SEV1),
				admin.getId()
		);
		EscalationTask task = escalationTaskRepository.findByIncidentId(inc.id()).orElseThrow();

		assertThat(escalationWorker.runOnce(task.getNextDueAt().minusSeconds(1))).isZero();
		assertThat(escalationWorker.runOnce(task.getNextDueAt())).isEqualTo(1);

		assertThat(escalationTaskRepository.findByIncidentId(inc.id()).orElseThrow().getCurrentStep()).isEqualTo(1);
		assertThat(incidentRepository.findById(inc.id()).orElseThrow().getVersion()).isEqualTo(1L);
		assertThat(outboxRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("INCIDENT", inc.id()))
				.extracting(Outbox::getStatus)
				.containsExactly(OutboxStatus.PENDING);
	}

	// Acknowledging is #83's work; until it lands, set the status a committed acknowledgement would leave.
	private void setStatus(Long incidentId, IncidentStatus status) {
		boolean inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
		if (inTransaction) {
			entityManager.flush();
		}
		jdbc.update("UPDATE incident SET status = ? WHERE id = ?", status.name(), incidentId);
		if (inTransaction) {
			entityManager.clear();
		}
	}
}
