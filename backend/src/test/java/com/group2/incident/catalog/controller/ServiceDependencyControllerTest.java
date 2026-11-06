package com.group2.incident.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.group2.incident.catalog.repository.ServiceDependencyRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.monitor.entity.UptimeMonitor;
import com.group2.incident.monitor.repository.UptimeMonitorRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import java.time.Clock;
import java.util.Arrays;
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
 * Dependencies, the service map, and the blast radius against PostgreSQL (FR-14.2, #154). Who may call
 * what is in SecurityMatrixTest; other orgs in OrgIsolationTest.
 */
@IntegrationTest
class ServiceDependencyControllerTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private ServiceDependencyRepository dependencies;

	@Autowired
	private UptimeMonitorRepository monitors;

	@Autowired
	private AuditEntryRepository auditEntries;

	@Autowired
	private Clock clock;

	private AppUser admin;

	private String adminToken;

	private Long serviceAId;
	private Long serviceBId;
	private Long serviceCId;
	private Long serviceDId;

	@BeforeEach
	void setUp() throws Exception {
		admin = accounts.create("deps_admin@test.local", Role.ADMIN);
		adminToken = accounts.token("deps_admin@test.local");

		serviceAId = createService("Auth Service", Criticality.CRITICAL);
		serviceBId = createService("Billing Service", Criticality.HIGH);
		serviceCId = createService("Cart Service", Criticality.MEDIUM);
		serviceDId = createService("Delivery Service", Criticality.LOW);
	}

	@Test
	@DisplayName("Admin successfully replaces dependencies")
	void adminSuccessfullyReplacesDependencies() throws Exception {
		setDependencies(serviceBId, serviceAId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.serviceId").value(serviceBId))
				.andExpect(jsonPath("$.dependsOn", contains(serviceAId.intValue())));

		assertThat(dependencies.findDependsOnIdsByServiceId(serviceBId)).containsExactly(serviceAId);
	}

	@Test
	@DisplayName("Replacing dependencies removes previous links that are no longer submitted")
	void replacingDependenciesRemovesPreviousLinks() throws Exception {
		setDependencies(serviceBId, serviceAId, serviceCId).andExpect(status().isOk());
		setDependencies(serviceBId, serviceCId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dependsOn", contains(serviceCId.intValue())));

		assertThat(dependencies.findDependsOnIdsByServiceId(serviceBId)).containsExactly(serviceCId);
	}

	@Test
	@DisplayName("A body without dependsOn is refused and leaves the links in place")
	void missingDependsOnIsRefused() throws Exception {
		setDependencies(serviceBId, serviceAId).andExpect(status().isOk());

		for (String body : List.of("{}", "{\"dependOn\": []}", "{\"dependsOn\": null}")) {
			send(serviceBId, body)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors.dependsOn").exists());
		}
		assertThat(dependencies.findDependsOnIdsByServiceId(serviceBId)).containsExactly(serviceAId);
	}

	@Test
	@DisplayName("An empty list removes every dependency")
	void emptyListRemovesEveryDependency() throws Exception {
		setDependencies(serviceBId, serviceAId).andExpect(status().isOk());
		send(serviceBId, "{\"dependsOn\": []}").andExpect(status().isOk());

		assertThat(dependencies.findDependsOnIdsByServiceId(serviceBId)).isEmpty();
	}

	@Test
	@DisplayName("Self-dependency returns HTTP 400")
	void selfDependencyReturns400() throws Exception {
		setDependencies(serviceAId, serviceAId)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.dependsOn").value("A service cannot depend on itself"));
	}

	@Test
	@DisplayName("Referenced nonexistent service ID returns HTTP 400")
	void nonexistentReferencedServiceIdReturns400() throws Exception {
		setDependencies(serviceAId, 999_999_999L)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.dependsOn").value("Service 999999999 not found"));
	}

	@Test
	@DisplayName("Nonexistent target service returns HTTP 404")
	void nonexistentTargetServiceReturns404() throws Exception {
		setDependencies(999_999_999L, serviceAId).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("Successful dependency changes are audited; no-op update is not audited")
	void dependencyChangesAreAuditedAndNoopSkipped() throws Exception {
		setDependencies(serviceBId, serviceAId).andExpect(status().isOk());
		setDependencies(serviceBId, serviceAId).andExpect(status().isOk());

		List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(AuditItemType.SERVICE, serviceBId);
		assertThat(entries).singleElement().satisfies(entry -> {
			assertThat(entry.getAction()).isEqualTo(AuditAction.SERVICE_DEPENDENCIES_UPDATED);
			assertThat(entry.getActor().getId()).isEqualTo(admin.getId());
			assertThat(entry.getDetails()).isEqualTo("Billing Service depends on: [] -> [Auth Service]");
		});
	}

	@Test
	@DisplayName("Duplicates in requested dependencies are deduplicated")
	void duplicateDependenciesAreDeduplicated() throws Exception {
		setDependencies(serviceBId, serviceAId, serviceAId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dependsOn", contains(serviceAId.intValue())));
	}

	@Test
	@DisplayName("Service map returns nodes with id, name, criticality, and health")
	void serviceMapReturnsNodesWithExpectedFields() throws Exception {
		mvc.perform(get("/api/service-map").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nodes[?(@.id == %d)].name".formatted(serviceAId)).value("Auth Service"))
				.andExpect(jsonPath("$.nodes[?(@.id == %d)].criticality".formatted(serviceAId)).value("CRITICAL"))
				.andExpect(jsonPath("$.nodes[?(@.id == %d)].health".formatted(serviceAId)).value("UNKNOWN"));
	}

	@Test
	@DisplayName("Service map returns edges with direction from dependent to dependency")
	void serviceMapReturnsEdgesWithCorrectDirection() throws Exception {
		setDependencies(serviceBId, serviceAId).andExpect(status().isOk());
		setDependencies(serviceCId, serviceBId).andExpect(status().isOk());

		mvc.perform(get("/api/service-map").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.edges", hasSize(2)))
				.andExpect(jsonPath("$.edges[0].from").value(serviceBId))
				.andExpect(jsonPath("$.edges[0].to").value(serviceAId))
				.andExpect(jsonPath("$.edges[1].from").value(serviceCId))
				.andExpect(jsonPath("$.edges[1].to").value(serviceBId));
	}

	@Test
	@DisplayName("Health comes from the monitors: unknown, operational, degraded, down, and the worst one wins")
	void healthComesFromTheMonitors() throws Exception {
		// A: no monitor. B: passing. C: failed once of three. D: failed three of three.
		monitor(serviceBId).recordSuccess(clock.instant());
		monitor(serviceCId).recordFailure(clock.instant());
		UptimeMonitor down = monitor(serviceDId);
		for (int i = 0; i < 3; i++) {
			down.recordFailure(clock.instant());
		}
		// A second, passing monitor on D does not hide the failing one.
		monitor(serviceDId).recordSuccess(clock.instant());
		monitors.flush();

		mvc.perform(get("/api/service-map").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nodes[?(@.id == %d)].health".formatted(serviceAId)).value("UNKNOWN"))
				.andExpect(jsonPath("$.nodes[?(@.id == %d)].health".formatted(serviceBId)).value("OPERATIONAL"))
				.andExpect(jsonPath("$.nodes[?(@.id == %d)].health".formatted(serviceCId)).value("DEGRADED"))
				.andExpect(jsonPath("$.nodes[?(@.id == %d)].health".formatted(serviceDId)).value("DOWN"));
	}

	@Test
	@DisplayName("Blast radius returns direct dependents")
	void blastRadiusDirectDependents() throws Exception {
		setDependencies(serviceBId, serviceAId).andExpect(status().isOk());

		blastRadius(serviceAId)
				.andExpect(jsonPath("$[*].id", contains(serviceBId.intValue())));
	}

	@Test
	@DisplayName("Blast radius returns transitive dependents across multiple levels")
	void blastRadiusTransitiveDependents() throws Exception {
		// C depends on B, which depends on A: A failing breaks both.
		setDependencies(serviceBId, serviceAId).andExpect(status().isOk());
		setDependencies(serviceCId, serviceBId).andExpect(status().isOk());

		blastRadius(serviceAId)
				.andExpect(jsonPath("$[*].id", containsInAnyOrder(serviceBId.intValue(), serviceCId.intValue())));
	}

	@Test
	@DisplayName("Blast radius terminates on a dependency cycle and leaves out the service itself")
	void blastRadiusHandlesCyclesSafely() throws Exception {
		// A -> B -> C -> A
		setDependencies(serviceAId, serviceBId).andExpect(status().isOk());
		setDependencies(serviceBId, serviceCId).andExpect(status().isOk());
		setDependencies(serviceCId, serviceAId).andExpect(status().isOk());

		blastRadius(serviceAId)
				.andExpect(jsonPath("$[*].id", containsInAnyOrder(serviceBId.intValue(), serviceCId.intValue())));
	}

	@Test
	@DisplayName("Blast radius returns each affected service at most once on multiple dependency paths")
	void blastRadiusReturnsEachAffectedServiceAtMostOnce() throws Exception {
		// Diamond: D depends on B and C; B and C both depend on A.
		setDependencies(serviceBId, serviceAId).andExpect(status().isOk());
		setDependencies(serviceCId, serviceAId).andExpect(status().isOk());
		setDependencies(serviceDId, serviceBId, serviceCId).andExpect(status().isOk());

		blastRadius(serviceAId)
				.andExpect(jsonPath("$[*].id", containsInAnyOrder(serviceBId.intValue(), serviceCId.intValue(), serviceDId.intValue())));
	}

	@Test
	@DisplayName("Blast radius returns empty list when service has no dependents")
	void blastRadiusReturnsEmptyListWhenNoDependents() throws Exception {
		blastRadius(serviceDId).andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	@DisplayName("Blast radius returns HTTP 404 for nonexistent service")
	void blastRadiusNonexistentServiceReturns404() throws Exception {
		mvc.perform(get("/api/services/999999999/blast-radius").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isNotFound());
	}

	private Long createService(String name, Criticality criticality) {
		ServiceEntity entity = new ServiceEntity(name, name + " description", admin, criticality, clock.instant());
		for (Severity severity : Severity.values()) {
			entity.putTarget(severity, 15, 240);
		}
		return services.saveAndFlush(entity).getId();
	}

	// A documentation-only address (RFC 5737); the worker is off in tests, so nothing is contacted.
	private UptimeMonitor monitor(Long serviceId) {
		ServiceEntity service = services.findById(serviceId).orElseThrow();
		return monitors.save(new UptimeMonitor(service, "Health", "https://203.0.113.10/health", 200, 60, 3, true, clock.instant()));
	}

	private ResultActions setDependencies(Long serviceId, Long... dependsOn) throws Exception {
		List<String> ids = Arrays.stream(dependsOn).map(String::valueOf).toList();
		return send(serviceId, "{\"dependsOn\": [" + String.join(", ", ids) + "]}");
	}

	private ResultActions send(Long serviceId, String body) throws Exception {
		return mvc.perform(put("/api/services/{id}/dependencies", serviceId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions blastRadius(Long serviceId) throws Exception {
		return mvc.perform(get("/api/services/{id}/blast-radius", serviceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk());
	}
}
