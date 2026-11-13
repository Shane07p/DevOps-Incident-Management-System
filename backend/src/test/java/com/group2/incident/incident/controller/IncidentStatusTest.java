package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Integration tests for incident status transitions (FR-06, US-10).
 *
 * Verifies:
 * - TC-FR-06.1a: Walk OPEN through to CLOSED on the normal path
 * - TC-FR-06.4b: Stale incident version returns 409
 * - Recovery note requirement for transition to RESOLVED
 * - Illegal transitions rejected with 409 without modifying the incident
 * - Role-based authorization (Admin & Engineer allowed, Viewer 403, unauthenticated 401)
 * - Timeline recording of status changes with actor and timestamp
 */
@IntegrationTest
class IncidentStatusTest {

	private static final String PASSWORD = "DevPassw0rd!";

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private TimelineEntryRepository timelineEntries;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	private AppUser admin;
	private AppUser engineer;
	private AppUser viewer;
	private ServiceEntity paymentsService;
	private long nextIncidentNumber;

	@BeforeEach
	void setUp() {
		Instant now = clock.instant();
		nextIncidentNumber = 100L;

		admin = users.findByEmail("status-admin@test.local")
				.orElseGet(() -> accounts.save(new AppUser("status-admin@test.local", "Status Admin", passwordEncoder.encode(PASSWORD), now), Role.ADMIN));
		engineer = users.findByEmail("status-engineer@test.local")
				.orElseGet(() -> accounts.save(new AppUser("status-engineer@test.local", "Status Engineer", passwordEncoder.encode(PASSWORD), now), Role.ENGINEER));
		viewer = users.findByEmail("status-viewer@test.local")
				.orElseGet(() -> accounts.save(new AppUser("status-viewer@test.local", "Status Viewer", passwordEncoder.encode(PASSWORD), now), Role.VIEWER));

		paymentsService = services.findByNameIgnoreCase("StatusPayments")
				.orElseGet(() -> {
					ServiceEntity s = new ServiceEntity("StatusPayments", "Payments service for status test", engineer, Criticality.CRITICAL, now);
					s.putTarget(Severity.SEV1, 15, 240);
					s.putTarget(Severity.SEV2, 60, 480);
					s.putTarget(Severity.SEV3, 240, 2880);
					s.putTarget(Severity.SEV4, 1440, 10080);
					return services.saveAndFlush(s);
				});
	}

	@Test
	@DisplayName("TC-FR-06.1a: Walk OPEN through to CLOSED on the normal path")
	void tcFr06_1a_walkOpenThroughToClosedOnNormalPath() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer);
		String token = tokenFor("status-engineer");
		long currentVersion = incident.getVersion();

		// Step 1: OPEN -> ACKNOWLEDGED, which happens only through acknowledgement (#83)
		String bodyAck = "{\"expectedVersion\":%d}".formatted(currentVersion);
		MvcResult resAck = mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(bodyAck))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
				.andExpect(jsonPath("$.version").value(currentVersion + 1))
				.andReturn();
		currentVersion = JsonPath.<Number>read(resAck.getResponse().getContentAsString(), "$.version").longValue();

		// Step 2: ACKNOWLEDGED -> INVESTIGATING
		String bodyInv = "{\"status\":\"INVESTIGATING\",\"expectedVersion\":%d}".formatted(currentVersion);
		MvcResult resInv = mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(bodyInv))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("INVESTIGATING"))
				.andExpect(jsonPath("$.version").value(currentVersion + 1))
				.andReturn();
		currentVersion = JsonPath.<Number>read(resInv.getResponse().getContentAsString(), "$.version").longValue();

		// Step 3: INVESTIGATING -> IDENTIFIED
		String bodyIdn = "{\"status\":\"IDENTIFIED\",\"expectedVersion\":%d}".formatted(currentVersion);
		MvcResult resIdn = mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(bodyIdn))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("IDENTIFIED"))
				.andExpect(jsonPath("$.version").value(currentVersion + 1))
				.andReturn();
		currentVersion = JsonPath.<Number>read(resIdn.getResponse().getContentAsString(), "$.version").longValue();

		// Step 4: IDENTIFIED -> MONITORING
		String bodyMon = "{\"status\":\"MONITORING\",\"expectedVersion\":%d}".formatted(currentVersion);
		MvcResult resMon = mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(bodyMon))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("MONITORING"))
				.andExpect(jsonPath("$.version").value(currentVersion + 1))
				.andReturn();
		currentVersion = JsonPath.<Number>read(resMon.getResponse().getContentAsString(), "$.version").longValue();

		// Step 5: MONITORING -> RESOLVED (with non-blank recovery note)
		String bodyRes = "{\"status\":\"RESOLVED\",\"expectedVersion\":%d,\"note\":\"Applied hotfix to connection pool\"}".formatted(currentVersion);
		MvcResult resResolved = mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(bodyRes))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESOLVED"))
				.andExpect(jsonPath("$.version").value(currentVersion + 1))
				.andExpect(jsonPath("$.resolvedAt").isNotEmpty())
				.andReturn();
		currentVersion = JsonPath.<Number>read(resResolved.getResponse().getContentAsString(), "$.version").longValue();

		// Step 6: RESOLVED -> CLOSED
		String bodyClose = "{\"status\":\"CLOSED\",\"expectedVersion\":%d}".formatted(currentVersion);
		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(bodyClose))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CLOSED"))
				.andExpect(jsonPath("$.version").value(currentVersion + 1));

		// Verify persisted state and timeline in repository
		Incident closedIncident = incidents.findById(incident.getId()).orElseThrow();
		assertThat(closedIncident.getStatus()).isEqualTo(IncidentStatus.CLOSED);

		List<TimelineEntry> entries = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		List<TimelineEntry> statusChanges = entries.stream()
				.filter(e -> e.getEventType() == TimelineEventType.STATUS_CHANGED)
				.toList();
		assertThat(statusChanges).hasSize(6);

		// Verify RESOLVED timeline entry contains recovery note
		TimelineEntry resolvedEntry = statusChanges.stream()
				.filter(e -> e.getDetails().contains("RESOLVED"))
				.findFirst()
				.orElseThrow();
		assertThat(resolvedEntry.getDetails()).contains("Applied hotfix to connection pool");
		assertThat(resolvedEntry.getActor().getId()).isEqualTo(engineer.getId());
	}

	@Test
	@DisplayName("TC-FR-06.4b: Negative - A change sent with a stale incident version returns 409; nothing changes")
	void tcFr06_4b_staleIncidentVersionReturns409() throws Exception {
		Incident incident = createIncident(IncidentStatus.ACKNOWLEDGED, engineer);
		long originalVersion = incident.getVersion();
		String token = tokenFor("status-engineer");

		// Stale version (originalVersion - 1)
		String body = "{\"status\":\"INVESTIGATING\",\"expectedVersion\":%d}".formatted(originalVersion - 1);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value(containsString("reload and try again")));

		Incident refreshed = incidents.findById(incident.getId()).orElseThrow();
		assertThat(refreshed.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(refreshed.getVersion()).isEqualTo(originalVersion);
	}

	@Test
	@DisplayName("Negative: Transition to RESOLVED without recovery note returns 400 Bad Request; incident unchanged")
	void transitionToResolved_missingRecoveryNote_returns400() throws Exception {
		Incident incident = createIncident(IncidentStatus.MONITORING, engineer);
		String token = tokenFor("status-engineer");

		String body = "{\"status\":\"RESOLVED\",\"expectedVersion\":%d}".formatted(incident.getVersion());

		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.note").value("A recovery note is required to resolve an incident"));

		Incident refreshed = incidents.findById(incident.getId()).orElseThrow();
		assertThat(refreshed.getStatus()).isEqualTo(IncidentStatus.MONITORING);
	}

	@Test
	@DisplayName("Negative: Transition to RESOLVED with blank recovery note returns 400 Bad Request; incident unchanged")
	void transitionToResolved_blankRecoveryNote_returns400() throws Exception {
		Incident incident = createIncident(IncidentStatus.MONITORING, engineer);
		String token = tokenFor("status-engineer");

		String body = "{\"status\":\"RESOLVED\",\"expectedVersion\":%d,\"note\":\"   \"}".formatted(incident.getVersion());

		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.note").value("A recovery note is required to resolve an incident"));

		Incident refreshed = incidents.findById(incident.getId()).orElseThrow();
		assertThat(refreshed.getStatus()).isEqualTo(IncidentStatus.MONITORING);
	}

	@Test
	@DisplayName("Negative: Illegal transition (e.g. OPEN -> RESOLVED) returns 409 Conflict; incident unchanged")
	void illegalTransition_returns409_incidentUnchanged() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer);
		String token = tokenFor("status-engineer");

		String body = "{\"status\":\"RESOLVED\",\"expectedVersion\":%d,\"note\":\"fixed\"}".formatted(incident.getVersion());

		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Cannot transition incident from OPEN to RESOLVED"));

		Incident refreshed = incidents.findById(incident.getId()).orElseThrow();
		assertThat(refreshed.getStatus()).isEqualTo(IncidentStatus.OPEN);
	}

	@Test
	@DisplayName("Alternative path: INVESTIGATING -> MONITORING and MONITORING -> INVESTIGATING")
	void alternativePaths_investigatingToMonitoringAndBack() throws Exception {
		Incident incident = createIncident(IncidentStatus.INVESTIGATING, engineer);
		String token = tokenFor("status-engineer");

		// INVESTIGATING -> MONITORING
		String body1 = "{\"status\":\"MONITORING\",\"expectedVersion\":%d}".formatted(incident.getVersion());
		MvcResult res1 = mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("MONITORING"))
				.andReturn();
		long v1 = JsonPath.<Number>read(res1.getResponse().getContentAsString(), "$.version").longValue();

		// MONITORING -> INVESTIGATING
		String body2 = "{\"status\":\"INVESTIGATING\",\"expectedVersion\":%d}".formatted(v1);
		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body2))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("INVESTIGATING"));
	}

	@Test
	@DisplayName("Security: Unauthenticated request returns 401 Unauthorized")
	void unauthenticatedRequest_returns401() throws Exception {
		Incident incident = createIncident(IncidentStatus.ACKNOWLEDGED, engineer);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"INVESTIGATING\",\"expectedVersion\":0}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Security: Viewer role returns 403 Forbidden")
	void viewerRole_returns403() throws Exception {
		Incident incident = createIncident(IncidentStatus.ACKNOWLEDGED, engineer);
		String token = tokenFor("status-viewer");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"INVESTIGATING\",\"expectedVersion\":0}"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Security: Admin role can transition incident (200)")
	void adminRole_canTransitionIncident() throws Exception {
		Incident incident = createIncident(IncidentStatus.ACKNOWLEDGED, engineer);
		String token = tokenFor("status-admin");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"INVESTIGATING\",\"expectedVersion\":%d}".formatted(incident.getVersion())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("INVESTIGATING"));
	}

	@Test
	@DisplayName("Negative: Non-existent incident returns 404 Not Found")
	void nonExistentIncident_returns404() throws Exception {
		String token = tokenFor("status-engineer");

		mvc.perform(post("/api/incidents/999999/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"INVESTIGATING\",\"expectedVersion\":0}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Incident 999999 not found"));
	}

	@Test
	@DisplayName("A transition to ACKNOWLEDGED is refused: acknowledging has its own endpoint")
	void acknowledgingThroughTransitionsIsRefused() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer);
		long version = incident.getVersion();

		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor("status-engineer"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"ACKNOWLEDGED\",\"expectedVersion\":%d}".formatted(version)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Acknowledge the incident instead"));

		Incident unchanged = incidents.findById(incident.getId()).orElseThrow();
		assertThat(unchanged.getStatus()).isEqualTo(IncidentStatus.OPEN);
		assertThat(unchanged.getVersion()).isEqualTo(version);
	}

	@Test
	@DisplayName("FR-16.2: RESOLVED stops the resolution clock once; the due times stay as they were")
	void resolvingStopsTheResolutionClockAndKeepsTheTargets() throws Exception {
		Incident incident = createIncident(IncidentStatus.MONITORING, engineer);
		String token = tokenFor("status-engineer");
		String dueBefore = incident.getResolutionDueAt().toString();

		String resolved = mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"RESOLVED\",\"expectedVersion\":%d,\"note\":\"Rolled back\"}".formatted(incident.getVersion())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resolvedAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		String resolvedAt = JsonPath.read(resolved, "$.resolvedAt");
		assertThat(Instant.parse(JsonPath.read(resolved, "$.resolutionDueAt"))).isEqualTo(Instant.parse(dueBefore));

		long version = JsonPath.<Number>read(resolved, "$.version").longValue();
		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"CLOSED\",\"expectedVersion\":%d}".formatted(version)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resolvedAt").value(resolvedAt));
	}

	@Test
	@DisplayName("A note sent with any move goes on the timeline")
	void aNoteWithAnyMoveIsRecorded() throws Exception {
		Incident incident = createIncident(IncidentStatus.ACKNOWLEDGED, engineer);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/transitions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor("status-engineer"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"INVESTIGATING\",\"expectedVersion\":%d,\"note\":\"Checking the gateway\"}".formatted(incident.getVersion())))
				.andExpect(status().isOk());

		assertThat(timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId()))
				.extracting(TimelineEntry::getDetails)
				.contains("Status changed from ACKNOWLEDGED to INVESTIGATING: Checking the gateway");
	}

	private Incident createIncident(IncidentStatus status, AppUser assignee) {
		Incident incident = new Incident(
				"Test Incident " + nextIncidentNumber,
				"Testing incident status transitions",
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
