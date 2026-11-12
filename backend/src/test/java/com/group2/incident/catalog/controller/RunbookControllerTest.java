package com.group2.incident.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.RunbookRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.group2.incident.support.TestOrgs;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Runbooks against PostgreSQL (FR-25, #155). Who may call what is in SecurityMatrixTest; other orgs
 * in OrgIsolationTest.
 */
@IntegrationTest
class RunbookControllerTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private RunbookRepository runbooks;

	@Autowired
	private AuditEntryRepository auditEntries;

	@Autowired
	private Clock clock;

	private AppUser engineer;

	private String engineerToken;

	private String viewerToken;

	private Long serviceId;

	@BeforeEach
	void setUp() throws Exception {
		AppUser admin = accounts.create("runbook_admin@test.local", Role.ADMIN);
		engineer = accounts.create("runbook_engineer@test.local", Role.ENGINEER);
		engineerToken = accounts.token("runbook_engineer@test.local");
		accounts.create("runbook_viewer@test.local", Role.VIEWER);
		viewerToken = accounts.token("runbook_viewer@test.local");

		ServiceEntity service = new ServiceEntity("Payments", "Payment authorization", admin, Criticality.HIGH, clock.instant());
		for (Severity severity : Severity.values()) {
			service.putTarget(severity, 15, 240);
		}
		serviceId = services.saveAndFlush(service).getId();
	}

	@Test
	@DisplayName("TC-FR-25.1a: a runbook with three steps is read back with its steps in the same order")
	void stepsKeepTheirOrder() throws Exception {
		long id = TestOrgs.id(create(serviceId, """
				{"title": "  Gateway timeouts  ", "steps": ["Check the gateway dashboard", "  Fail over to the backup gateway ", "Tell Comms"]}""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.serviceId").value(serviceId))
				.andExpect(jsonPath("$.title").value("Gateway timeouts"))
				.andReturn().getResponse().getContentAsString());

		mvc.perform(get("/api/services/{id}/runbooks", serviceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(id))
				.andExpect(jsonPath("$[0].steps", contains("Check the gateway dashboard", "Fail over to the backup gateway", "Tell Comms")));
	}

	@Test
	@DisplayName("TC-FR-25.1a: editing a runbook replaces its steps, in the new order")
	void editReplacesTheSteps() throws Exception {
		long id = created("Gateway timeouts", "First", "Second", "Third");

		send(put("/api/runbooks/{id}", id), """
				{"title": "Gateway timeouts", "steps": ["Third", "First"]}""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps", contains("Third", "First")));

		assertThat(runbooks.findWithStepsById(id).orElseThrow().getSteps()).containsExactly("Third", "First");
	}

	@Test
	@DisplayName("TC-FR-25.1b: an empty title, no steps, or a blank step is refused with a field error, and nothing is saved")
	void invalidRunbooksAreRefused() throws Exception {
		create(serviceId, """
				{"title": "   ", "steps": ["Restart"]}""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.title").value("Title is required"));
		create(serviceId, """
				{"title": "Restart", "steps": []}""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.steps").value("Add at least one step"));
		create(serviceId, """
				{"title": "Restart"}""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.steps").value("Add at least one step"));
		create(serviceId, """
				{"title": "Restart", "steps": ["Stop it", " "]}""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors['steps[1]']").value("A step cannot be empty"));

		assertThat(runbooks.findAllByServiceIdOrderByTitleAscIdAsc(serviceId)).isEmpty();
	}

	@Test
	@DisplayName("TC-FR-25.1d: creating, editing, and deleting a runbook write one audit entry each; a save that changes nothing writes none")
	void everyChangeIsAudited() throws Exception {
		long id = created("Gateway timeouts", "Check the gateway");
		send(put("/api/runbooks/{id}", id), """
				{"title": "Gateway timeouts", "steps": ["Check the gateway"]}""").andExpect(status().isOk());
		send(put("/api/runbooks/{id}", id), """
				{"title": "Gateway timeouts", "steps": ["Check the gateway", "Fail over"]}""").andExpect(status().isOk());
		send(delete("/api/runbooks/{id}", id), null).andExpect(status().isNoContent());

		List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(AuditItemType.RUNBOOK, id);
		assertThat(entries).extracting(AuditEntry::getAction)
				.containsExactlyInAnyOrder(AuditAction.RUNBOOK_CREATED, AuditAction.RUNBOOK_UPDATED, AuditAction.RUNBOOK_DELETED);
		assertThat(entries).allSatisfy(entry -> assertThat(entry.getActor().getId()).isEqualTo(engineer.getId()));
		assertThat(entries).filteredOn(entry -> entry.getAction() == AuditAction.RUNBOOK_UPDATED).singleElement()
				.satisfies(entry -> assertThat(entry.getDetails())
						.isEqualTo("Was: \"Gateway timeouts\" for Payments, 1 step. Now: \"Gateway timeouts\" for Payments, 2 steps"));
		assertThat(runbooks.findById(id)).isEmpty();
	}

	@Test
	@DisplayName("A service's runbooks list only its own, by title")
	void listShowsOnlyThatServicesRunbooks() throws Exception {
		AppUser owner = engineer;
		ServiceEntity other = new ServiceEntity("Search", "Product search", owner, Criticality.LOW, clock.instant());
		for (Severity severity : Severity.values()) {
			other.putTarget(severity, 15, 240);
		}
		Long otherServiceId = services.saveAndFlush(other).getId();
		created("Slow checkout", "Check the queue");
		created("Gateway timeouts", "Check the gateway");
		create(otherServiceId, """
				{"title": "Search down", "steps": ["Restart search"]}""").andExpect(status().isCreated());

		mvc.perform(get("/api/services/{id}/runbooks", serviceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].title", contains("Gateway timeouts", "Slow checkout")));
	}

	@Test
	@DisplayName("An unknown service or runbook is 404")
	void unknownIdsAreNotFound() throws Exception {
		mvc.perform(get("/api/services/{id}/runbooks", 999_999L).header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isNotFound());
		create(999_999L, """
				{"title": "Restart", "steps": ["Restart"]}""").andExpect(status().isNotFound());
		send(put("/api/runbooks/{id}", 999_999L), """
				{"title": "Restart", "steps": ["Restart"]}""").andExpect(status().isNotFound());
		send(delete("/api/runbooks/{id}", 999_999L), null).andExpect(status().isNotFound());
	}

	private long created(String title, String... steps) throws Exception {
		String body = "{\"title\": \"%s\", \"steps\": [%s]}".formatted(title,
				String.join(", ", java.util.Arrays.stream(steps).map(step -> "\"" + step + "\"").toList()));
		return TestOrgs.id(create(serviceId, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
	}

	private ResultActions create(Long service, String body) throws Exception {
		return send(post("/api/services/{id}/runbooks", service), body);
	}

	private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body)
			throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken);
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}
}
