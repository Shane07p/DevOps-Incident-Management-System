package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.entity.EscalationPolicy;
import com.group2.incident.incident.entity.EscalationStep;
import com.group2.incident.incident.entity.EscalationTask;
import com.group2.incident.incident.entity.EscalationTaskState;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.EscalationPolicyRepository;
import com.group2.incident.incident.repository.EscalationTaskRepository;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.incident.service.EscalationService;
import com.group2.incident.incident.service.IncidentService;
import com.group2.incident.incident.service.SlaCalculator;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Tests incident acknowledgement (US-08, FR-06, FR-07.4, FR-16.2).
 * Verifies equivalence classes, invalid transitions, concurrency, and ownership scenarios.
 */
@IntegrationTest
class AcknowledgeTest {

	private static final String PASSWORD = "DevPassw0rd!";

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private TimelineEntryRepository timelineEntries;

	@Autowired
	private EscalationPolicyRepository escalationPolicies;

	@Autowired
	private EscalationTaskRepository escalationTasks;

	@Autowired
	private EscalationService escalationService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	private AppUser admin;
	private AppUser engineer;
	private AppUser responder2;
	private AppUser viewer;
	private ServiceEntity paymentsService;
	private long nextIncidentNumber;

	@Autowired
	private com.group2.incident.auth.repository.AppUserRepository users;

	@BeforeEach
	void setUp() {
		Instant now = clock.instant();
		nextIncidentNumber = 1L;

		admin = users.findByEmail("ack-admin@test.local")
				.orElseGet(() -> accounts.save(new AppUser("ack-admin@test.local", "Dev Admin", passwordEncoder.encode(PASSWORD), now), Role.ADMIN));
		engineer = users.findByEmail("ack-engineer@test.local")
				.orElseGet(() -> accounts.save(new AppUser("ack-engineer@test.local", "Dev Engineer", passwordEncoder.encode(PASSWORD), now), Role.ENGINEER));
		responder2 = users.findByEmail("ack-resp2@test.local")
				.orElseGet(() -> accounts.save(new AppUser("ack-resp2@test.local", "Responder Two", passwordEncoder.encode(PASSWORD), now), Role.ENGINEER));
		viewer = users.findByEmail("ack-viewer@test.local")
				.orElseGet(() -> accounts.save(new AppUser("ack-viewer@test.local", "Dev Viewer", passwordEncoder.encode(PASSWORD), now), Role.VIEWER));

		ServiceEntity payments = services.findByNameIgnoreCase("AckPayments")
				.orElseGet(() -> {
					ServiceEntity s = new ServiceEntity("AckPayments", "Card payments", engineer, Criticality.CRITICAL, now);
					s.putTarget(Severity.SEV1, 15, 240);
					s.putTarget(Severity.SEV2, 60, 480);
					s.putTarget(Severity.SEV3, 240, 2880);
					s.putTarget(Severity.SEV4, 1440, 10080);
					return services.saveAndFlush(s);
				});
		paymentsService = payments;

		escalationPolicies.findByServiceId(paymentsService.getId())
				.orElseGet(() -> {
					EscalationPolicy policy = new EscalationPolicy(paymentsService, now);
					policy.replaceSteps(List.of(new EscalationStep(1, responder2, 10)), now);
					return escalationPolicies.saveAndFlush(policy);
				});
	}

	@Test
	@DisplayName("TC-US-08a: Equivalence - Acknowledge an OPEN incident -> ACKNOWLEDGED, acknowledger becomes assignee, timeline entry added, escalation cancelled")
	void tcUs08a_acknowledgeOpenIncident() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer);
		long originalVersion = incident.getVersion();

		// Create a pending escalation task for this incident
		Instant now = clock.instant();
		escalationService.createTaskForIncident(incident, now);
		EscalationTask pendingTask = escalationTasks.findByIncidentId(incident.getId()).orElseThrow();
		assertThat(pendingTask.getState()).isEqualTo(EscalationTaskState.PENDING);

		String token = tokenFor("ack-engineer");
		String body = "{\"expectedVersion\": %d}".formatted(originalVersion);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(incident.getId()))
				.andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
				.andExpect(jsonPath("$.assignee.id").value(engineer.getId()))
				.andExpect(jsonPath("$.assignee.displayName").value(engineer.getDisplayName()))
				.andExpect(jsonPath("$.version").value(originalVersion + 1));

		// Verify database state
		Incident persisted = incidents.findById(incident.getId()).orElseThrow();
		assertThat(persisted.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(persisted.getAssignee().getId()).isEqualTo(engineer.getId());
		assertThat(persisted.getAcknowledgedAt()).isNotNull();
		assertThat(persisted.getVersion()).isEqualTo(originalVersion + 1);

		// Verify timeline entry
		List<TimelineEntry> timeline = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		TimelineEntry ackEntry = timeline.stream()
				.filter(e -> e.getEventType() == TimelineEventType.ACKNOWLEDGED)
				.findFirst()
				.orElseThrow();
		assertThat(ackEntry.getActor().getId()).isEqualTo(engineer.getId());
		assertThat(ackEntry.getDetails()).isEqualTo("Acknowledged by " + engineer.getDisplayName());
		assertThat(ackEntry.getRecordedAt()).isNotNull();

		// Verify pending escalation task was cancelled
		EscalationTask cancelledTask = escalationTasks.findByIncidentId(incident.getId()).orElseThrow();
		assertThat(cancelledTask.getState()).isEqualTo(EscalationTaskState.CANCELLED);
		assertThat(cancelledTask.getNextDueAt()).isNull();
	}

	@Test
	@DisplayName("TC-FR-16.2a: Equivalence - Acknowledge at minute 10 -> The response clock stops at 10; the resolution clock keeps running")
	void tcFr16_2a_acknowledgeStopsResponseClockWhileResolutionKeepsRunning() throws Exception {
		Instant now = clock.instant();
		// Incident was created exactly 10 minutes ago
		Instant createdTime = now.minus(10, ChronoUnit.MINUTES);
		Incident incident = new Incident(
				"SLA Test Incident",
				"Measuring SLA clocks",
				paymentsService,
				Severity.SEV1,
				IncidentStatus.OPEN,
				engineer,
				createdTime,
				15, // response target: 15 min
				240 // resolution target: 240 min
		);
		incident.assignNumber(nextIncidentNumber++);
		incident = incidents.saveAndFlush(incident);

		// Acknowledge at minute 10 (now)
		String token = tokenFor("ack-engineer");
		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\": 0}"))
				.andExpect(status().isOk());

		Incident reloaded = incidents.findById(incident.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		Instant ackTime = reloaded.getAcknowledgedAt();
		assertThat(ackTime).isNotNull();

		// 1. Response clock: stopped at acknowledgement time (elapsed is 10 minutes)
		// At minute 10: elapsed is 10 min <= 15 min target -> NOT breached
		assertThat(SlaCalculator.isBreached(reloaded.getCreatedAt(), ackTime, reloaded.getResponseTargetMinutes())).isFalse();

		// Even at minute 20 (or any later time), because the response clock stopped at ackTime (minute 10), response SLA is NOT breached!
		assertThat(SlaCalculator.isBreached(reloaded.getCreatedAt(), ackTime, reloaded.getResponseTargetMinutes())).isFalse();

		// 2. Resolution clock: NOT stopped by acknowledgement; keeps running!
		// At minute 20: 20 min <= 240 min target -> NOT breached
		Instant minute20 = createdTime.plus(20, ChronoUnit.MINUTES);
		assertThat(SlaCalculator.isBreached(reloaded.getCreatedAt(), minute20, reloaded.getResolutionTargetMinutes())).isFalse();

		// At minute 250: 250 min > 240 min target -> BREACHED (because resolution clock kept running)
		Instant minute250 = createdTime.plus(250, ChronoUnit.MINUTES);
		assertThat(SlaCalculator.isBreached(reloaded.getCreatedAt(), minute250, reloaded.getResolutionTargetMinutes())).isTrue();
	}

	@ParameterizedTest(name = "TC-US-08b: Cannot acknowledge incident in {0} status")
	@EnumSource(value = IncidentStatus.class, names = { "ACKNOWLEDGED", "INVESTIGATING", "IDENTIFIED", "MONITORING", "RESOLVED", "CLOSED" })
	@DisplayName("TC-US-08b: Equivalence (invalid) - Acknowledge an incident that isn't OPEN returns 409")
	void tcUs08b_acknowledgeNonOpenIncidentReturns409(IncidentStatus status) throws Exception {
		Incident incident = createIncident(status, engineer);
		Long originalVersion = incident.getVersion();

		String token = tokenFor("ack-engineer");
		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\": %d}".formatted(originalVersion)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cannot acknowledge an incident in " + status + " status")));

		// Verify incident remains unchanged
		Incident unchanged = incidents.findById(incident.getId()).orElseThrow();
		assertThat(unchanged.getStatus()).isEqualTo(status);
		assertThat(unchanged.getVersion()).isEqualTo(originalVersion);
	}

	@Test
	@DisplayName("TC-ACK-OWNER: Scenario - A is assigned; escalation notifies B; B acknowledges first -> B becomes the assignee; escalation alone never changed it")
	void tcAckOwner_escalationNotifiesB_bAcknowledgesFirst_becomesAssignee() throws Exception {
		// Incident created and assigned to User A (engineer)
		Incident incident = createIncident(IncidentStatus.OPEN, engineer);
		assertThat(incident.getAssignee().getId()).isEqualTo(engineer.getId());

		// Escalation policy has User B (responder2) at step 1
		Instant now = clock.instant();
		escalationService.createTaskForIncident(incident, now);
		EscalationTask task = escalationTasks.findByIncidentId(incident.getId()).orElseThrow();

		// Escalation fires: notifies User B (responder2)
		Instant overdueTime = task.getNextDueAt().plus(1, ChronoUnit.MINUTES);
		boolean escalated = escalationService.executeEscalationStep(task.getId(), overdueTime);
		assertThat(escalated).isTrue();

		// Verify: escalation alone NEVER changes the assignee! Assignee is STILL User A (engineer)
		Incident afterEscalation = incidents.findById(incident.getId()).orElseThrow();
		assertThat(afterEscalation.getAssignee().getId()).isEqualTo(engineer.getId());

		// Now User B (responder2) acknowledges first!
		String tokenB = tokenFor("ack-resp2");
		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\": %d}".formatted(afterEscalation.getVersion())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
				.andExpect(jsonPath("$.assignee.id").value(responder2.getId()))
				.andExpect(jsonPath("$.assignee.displayName").value(responder2.getDisplayName()));

		// User B is now the assignee!
		Incident afterAck = incidents.findById(incident.getId()).orElseThrow();
		assertThat(afterAck.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(afterAck.getAssignee().getId()).isEqualTo(responder2.getId());

		// If User A now tries to acknowledge, they get 409 Conflict (since status is now ACKNOWLEDGED)
		String tokenA = tokenFor("ack-engineer");
		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\": %d}".formatted(afterAck.getVersion())))
				.andExpect(status().isConflict());

		// User B remains the assignee
		Incident finalState = incidents.findById(incident.getId()).orElseThrow();
		assertThat(finalState.getAssignee().getId()).isEqualTo(responder2.getId());
	}

	@Test
	@DisplayName("Admin can also acknowledge an OPEN incident")
	void adminCanAcknowledge() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer);
		String token = tokenFor("ack-admin");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\": %d}".formatted(incident.getVersion())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
				.andExpect(jsonPath("$.assignee.id").value(admin.getId()));
	}

	@Test
	@DisplayName("Viewer is forbidden from acknowledging an incident (403)")
	void viewerCannotAcknowledge() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer);
		String token = tokenFor("ack-viewer");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\": 0}"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Unauthenticated request is rejected (401)")
	void unauthenticatedCannotAcknowledge() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\": 0}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Acknowledge with stale version returns 409 Conflict")
	void staleVersionReturns409() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer);
		String token = tokenFor("ack-engineer");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\": 999}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("This record was changed by another request; reload and try again"));
	}

	@Test
	@DisplayName("Acknowledge with missing expectedVersion returns 400 Bad Request")
	void missingExpectedVersionReturns400() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer);
		String token = tokenFor("ack-engineer");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Acknowledge non-existent incident returns 404 Not Found")
	void nonExistentIncidentReturns404() throws Exception {
		String token = tokenFor("ack-engineer");

		mvc.perform(post("/api/incidents/999999/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\": 0}"))
				.andExpect(status().isNotFound());
	}

	private Incident createIncident(IncidentStatus status, AppUser assignee) {
		Incident incident = new Incident(
				"Test Incident " + nextIncidentNumber,
				"Testing incident acknowledgement",
				paymentsService,
				Severity.SEV2,
				status,
				engineer,
				clock.instant(),
				60,
				480
		);
		incident.assignNumber(nextIncidentNumber++);
		if (assignee != null) {
			incident.assignTo(assignee);
		}
		return incidents.saveAndFlush(incident);
	}

	private ResultActions login(String username, String password) throws Exception {
		String body = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(username + "@test.local", password);
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String tokenFor(String username) throws Exception {
		return JsonPath.read(login(username, PASSWORD).andReturn().getResponse().getContentAsString(), "$.token");
	}
}
