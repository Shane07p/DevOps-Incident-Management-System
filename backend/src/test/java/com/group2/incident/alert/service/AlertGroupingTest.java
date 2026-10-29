package com.group2.incident.alert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.alert.dto.AlertAcceptedResponse;
import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.service.IncidentService;
import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
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

/** The deduplication and grouping decision table (FR-03.2 to FR-03.4), against PostgreSQL. */
@IntegrationTest
class AlertGroupingTest {

	private static final String PASSWORD = "DevPassw0rd!";
	private static final String PAYMENTS = "Grouping Payments";
	private static final String ORDERS = "Grouping Orders";

	@Autowired
	private AlertService alertService;

	@Autowired
	private IncidentService incidentService;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private Clock clock;

	private final ExecutorService otherTool = Executors.newSingleThreadExecutor();

	private AppUser owner;
	private ServiceEntity payments;
	private ServiceEntity orders;

	@BeforeEach
	void setUp() {
		owner = users.saveAndFlush(new AppUser("grouping_owner", "Grouping Owner", passwordEncoder.encode(PASSWORD), Role.ENGINEER,
				clock.instant()));
		payments = createService(PAYMENTS);
		orders = createService(ORDERS);
	}

	// The tests outside the test transaction commit; delete just what they saved, children first.
	@AfterEach
	void tearDown() {
		otherTool.shutdownNow();
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return;
		}
		Object[] serviceIds = { payments.getId(), orders.getId() };
		String incidents = "SELECT id FROM incident WHERE service_id IN (?, ?)";
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			// Replica mode skips the append-only triggers, but also foreign keys and cascades, so it
			// covers only these two deletes.
			jdbc.execute("SET LOCAL session_replication_role = 'replica'");
			jdbc.update("DELETE FROM timeline_entry WHERE incident_id IN (" + incidents + ")", serviceIds);
			jdbc.update("DELETE FROM audit_entry WHERE item_type = 'INCIDENT' AND item_id IN (" + incidents + ")", serviceIds);
			jdbc.execute("SET LOCAL session_replication_role = 'origin'");
			jdbc.update("DELETE FROM alert WHERE service_id IN (?, ?)", serviceIds);
			jdbc.update("DELETE FROM incident WHERE service_id IN (?, ?)", serviceIds);
			jdbc.update("DELETE FROM service_sla_target WHERE service_id IN (?, ?)", serviceIds);
			jdbc.update("DELETE FROM service WHERE id IN (?, ?)", serviceIds);
			jdbc.update("DELETE FROM app_user WHERE id = ?", owner.getId());
		});
	}

	@ParameterizedTest
	@EnumSource(value = IncidentStatus.class, names = { "RESOLVED", "CLOSED" })
	void aKeyMatchingOnlyAFinishedIncidentOpensANewOne(IncidentStatus finished) {
		// TC-FR-03.4a, rule R4
		alertService.processAlert(null, alert("evt-1", PAYMENTS, Severity.SEV2, "pay-5xx"));
		Long first = incidentOf("evt-1");
		setStatus(first, finished);

		alertService.processAlert(null, alert("evt-2", PAYMENTS, Severity.SEV2, "pay-5xx"));

		assertThat(incidentOf("evt-2")).isNotEqualTo(first);
		assertThat(statusOf("evt-2")).isEqualTo("NEW");
	}

	@Test
	void joiningAnIncidentLeavesItsClocksAlone() {
		// TC-FR-03.3a, rule R3: a SEV1 alert joining a SEV2 incident changes neither due time
		alertService.processAlert(null, alert("evt-1", PAYMENTS, Severity.SEV2, "pay-5xx"));
		Long incidentId = incidentOf("evt-1");
		IncidentResponse before = incidentService.getIncident(incidentId);

		alertService.processAlert(null, alert("evt-2", PAYMENTS, Severity.SEV1, "pay-5xx"));

		IncidentResponse after = incidentService.getIncident(incidentId);
		assertThat(incidentOf("evt-2")).isEqualTo(incidentId);
		assertThat(after.responseDueAt()).isEqualTo(before.responseDueAt());
		assertThat(after.resolutionDueAt()).isEqualTo(before.resolutionDueAt());
		assertThat(statusOf("evt-1")).isEqualTo("NEW");
		assertThat(statusOf("evt-2")).isEqualTo("ATTACHED");
	}

	@Test
	void inboxListsAlertsNewestFirstWithAssigneeAndCount() throws Exception {
		alertService.processAlert(null, alert("evt-1", PAYMENTS, Severity.SEV2, "pay-5xx"));
		alertService.processAlert(null, alert("evt-2", PAYMENTS, Severity.SEV2, "pay-5xx"));
		alertService.processAlert(null, alert("evt-3", ORDERS, Severity.SEV2, null));

		mvc.perform(get("/api/alerts").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner.getUsername())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].serviceName").value(ORDERS))
				.andExpect(jsonPath("$[0].status").value("NEW"))
				.andExpect(jsonPath("$[0].count").value(1))
				.andExpect(jsonPath("$[1].status").value("ATTACHED"))
				.andExpect(jsonPath("$[1].count").value(2))
				.andExpect(jsonPath("$[1].assigneeId").value(owner.getId()))
				.andExpect(jsonPath("$[1].incidentId").value(incidentOf("evt-1")))
				.andExpect(jsonPath("$[2].status").value("NEW"))
				.andExpect(jsonPath("$[2].count").value(2));
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void sameEventTwiceAtOnceMakesOneAlertAndOneIncident() throws Exception {
		// TC-FR-03.2b
		AlertRequest event = alert("evt-race", PAYMENTS, Severity.SEV2, "pay-5xx");

		Future<AlertAcceptedResponse> retry = whileTheFirstIsOpen(event, event);

		assertThat(retry.get(30, TimeUnit.SECONDS).eventId()).isEqualTo("evt-race");
		assertThat(countFor("alert", payments)).isEqualTo(1);
		assertThat(countFor("incident", payments)).isEqualTo(1);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void twoEventsWithOneKeyAtOnceShareOneIncident() throws Exception {
		// TC-FR-03.3b
		Future<AlertAcceptedResponse> second = whileTheFirstIsOpen(
				alert("evt-a", PAYMENTS, Severity.SEV2, "pay-5xx"),
				alert("evt-b", PAYMENTS, Severity.SEV2, "pay-5xx"));

		second.get(30, TimeUnit.SECONDS);
		assertThat(countFor("alert", payments)).isEqualTo(2);
		assertThat(countFor("incident", payments)).isEqualTo(1);
		assertThat(statusOf("evt-b")).isEqualTo("ATTACHED");
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void oneEventForTwoServicesAtOnceGets409() throws Exception {
		// Each request locks only its own service, so the unique (source, event_id) catches the second.
		Future<AlertAcceptedResponse> second = whileTheFirstIsOpen(
				alert("evt-both", PAYMENTS, Severity.SEV2, null),
				alert("evt-both", ORDERS, Severity.SEV2, null));

		assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(ConflictException.class);
		assertThat(countFor("alert", payments)).isEqualTo(1);
		assertThat(countFor("incident", orders)).isZero();
	}

	// Sends `second` from another thread while `first` holds its transaction open, waits until it is
	// blocked on a lock, and then lets the first commit.
	private Future<AlertAcceptedResponse> whileTheFirstIsOpen(AlertRequest first, AlertRequest second) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			alertService.processAlert(null, first);
			Future<AlertAcceptedResponse> started = otherTool.submit(() -> alertService.processAlert(null, second));
			waitForASessionBlockedOnALock();
			return started;
		});
	}

	// Up to two seconds for the second request to reach the lock, so every run sees the same order.
	private void waitForASessionBlockedOnALock() {
		for (int i = 0; i < 40; i++) {
			Integer blocked = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock'", Integer.class);
			if (blocked != null && blocked > 0) {
				return;
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private static AlertRequest alert(String eventId, String service, Severity severity, String correlationKey) {
		return new AlertRequest("prometheus", eventId, service, "High error rate", "500 errors on /pay", severity.name(),
				correlationKey, Map.of("metric", "http_requests_total"));
	}

	private ServiceEntity createService(String name) {
		ServiceEntity service = new ServiceEntity(name, "Grouping test service", owner, Criticality.HIGH, clock.instant());
		service.putTarget(Severity.SEV1, 15, 120);
		service.putTarget(Severity.SEV2, 60, 480);
		// No shifts, so every incident goes to the fallback contact.
		service.changeFallbackContact(owner);
		return services.saveAndFlush(service);
	}

	private Long incidentOf(String eventId) {
		return jdbc.queryForObject("select incident_id from alert where event_id = ?", Long.class, eventId);
	}

	private String statusOf(String eventId) {
		return jdbc.queryForObject("select status from alert where event_id = ?", String.class, eventId);
	}

	private long countFor(String table, ServiceEntity service) {
		return jdbc.queryForObject("select count(*) from " + table + " where service_id = ?", Long.class, service.getId());
	}

	// Resolving and closing are #84's work; until they land, set the status those would leave.
	private void setStatus(Long incidentId, IncidentStatus status) {
		entityManager.flush();
		jdbc.update("UPDATE incident SET status = ? WHERE id = ?", status.name(), incidentId);
		entityManager.clear();
	}

	private String tokenFor(String username) throws Exception {
		String response = mvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.token");
	}
}
