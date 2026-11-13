package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Severity changes with a reason (FR-04.2, #84): the SLA targets and due times copied at creation stay fixed. */
@IntegrationTest
class SeverityChangeTest {

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

	private AppUser engineer;

	private ServiceEntity payments;

	@BeforeEach
	void setUp() {
		Instant now = clock.instant();
		engineer = users.findByEmail("severity-engineer@test.local")
				.orElseGet(() -> accounts.save(new AppUser("severity-engineer@test.local", "Severity Engineer",
						passwordEncoder.encode(PASSWORD), now), Role.ENGINEER));
		users.findByEmail("severity-viewer@test.local")
				.orElseGet(() -> accounts.save(new AppUser("severity-viewer@test.local", "Severity Viewer",
						passwordEncoder.encode(PASSWORD), now), Role.VIEWER));
		payments = services.findByNameIgnoreCase("SeverityPayments").orElseGet(() -> {
			ServiceEntity service = new ServiceEntity("SeverityPayments", "Payments", engineer, Criticality.CRITICAL, now);
			service.putTarget(Severity.SEV1, 15, 240);
			service.putTarget(Severity.SEV2, 60, 480);
			service.putTarget(Severity.SEV3, 240, 2880);
			service.putTarget(Severity.SEV4, 1440, 10080);
			return services.saveAndFlush(service);
		});
	}

	@Test
	@DisplayName("TC-FR-04.2a: a change with a reason is saved, on the timeline, and leaves the SLA targets as they were")
	void aChangeWithAReasonKeepsTheTargets() throws Exception {
		Incident incident = incident(IncidentStatus.INVESTIGATING);
		String responseDue = incident.getResponseDueAt().toString();
		String resolutionDue = incident.getResolutionDueAt().toString();

		String changed = change(incident, "{\"severity\":\"SEV1\",\"reason\":\"Checkout is failing for everyone\",\"expectedVersion\":%d}"
				.formatted(incident.getVersion()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.severity").value("SEV1"))
				.andExpect(jsonPath("$.responseTargetMinutes").value(60))
				.andExpect(jsonPath("$.resolutionTargetMinutes").value(480))
				.andExpect(jsonPath("$.version").value(incident.getVersion() + 1))
				.andReturn().getResponse().getContentAsString();

		assertThat(Instant.parse(JsonPath.read(changed, "$.responseDueAt"))).isEqualTo(Instant.parse(responseDue));
		assertThat(Instant.parse(JsonPath.read(changed, "$.resolutionDueAt"))).isEqualTo(Instant.parse(resolutionDue));
		TimelineEntry entry = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId()).getLast();
		assertThat(entry.getEventType()).isEqualTo(TimelineEventType.SEVERITY_CHANGED);
		assertThat(entry.getActor().getId()).isEqualTo(engineer.getId());
		assertThat(entry.getDetails()).isEqualTo("Severity changed from SEV2 to SEV1: Checkout is failing for everyone");
	}

	@Test
	@DisplayName("TC-FR-04.2b: a change with no reason is refused and nothing changes")
	void aChangeWithoutAReasonIsRefused() throws Exception {
		Incident incident = incident(IncidentStatus.INVESTIGATING);

		change(incident, "{\"severity\":\"SEV1\",\"reason\":\"  \",\"expectedVersion\":%d}".formatted(incident.getVersion()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.reason").exists());

		assertUnchanged(incident);
	}

	@Test
	@DisplayName("The same severity, a closed incident, or a stale version is refused with 409")
	void sameSeverityClosedOrStaleIsRefused() throws Exception {
		Incident open = incident(IncidentStatus.INVESTIGATING);
		change(open, "{\"severity\":\"SEV2\",\"reason\":\"No change\",\"expectedVersion\":%d}".formatted(open.getVersion()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("The incident is already SEV2"));
		change(open, "{\"severity\":\"SEV1\",\"reason\":\"Worse\",\"expectedVersion\":%d}".formatted(open.getVersion() + 5))
				.andExpect(status().isConflict());
		assertUnchanged(open);

		Incident closed = incident(IncidentStatus.CLOSED);
		change(closed, "{\"severity\":\"SEV1\",\"reason\":\"Worse\",\"expectedVersion\":%d}".formatted(closed.getVersion()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Cannot change severity on a closed incident"));
	}

	@Test
	@DisplayName("A Viewer can't change the severity")
	void aViewerIsRefused() throws Exception {
		Incident incident = incident(IncidentStatus.INVESTIGATING);

		mvc.perform(put("/api/incidents/{id}/severity", incident.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor("severity-viewer"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"severity\":\"SEV1\",\"reason\":\"Worse\",\"expectedVersion\":%d}".formatted(incident.getVersion())))
				.andExpect(status().isForbidden());

		assertUnchanged(incident);
	}

	private void assertUnchanged(Incident incident) {
		Incident reloaded = incidents.findById(incident.getId()).orElseThrow();
		assertThat(reloaded.getSeverity()).isEqualTo(Severity.SEV2);
		assertThat(reloaded.getVersion()).isEqualTo(incident.getVersion());
		assertThat(timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId()))
				.noneMatch(entry -> entry.getEventType() == TimelineEventType.SEVERITY_CHANGED);
	}

	private ResultActions change(Incident incident, String body) throws Exception {
		return mvc.perform(put("/api/incidents/{id}/severity", incident.getId())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor("severity-engineer"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private Incident incident(IncidentStatus status) {
		Incident incident = new Incident("Severity test", "Testing severity changes", payments, Severity.SEV2, status,
				engineer, clock.instant(), 60, 480);
		incident.assignNumber(System.nanoTime() % 1_000_000L);
		return incidents.saveAndFlush(incident);
	}

	private String tokenFor(String username) throws Exception {
		String body = "{\"email\":\"%s@test.local\",\"password\":\"%s\"}".formatted(username, PASSWORD);
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn().getResponse().getContentAsString(), "$.token");
	}
}
