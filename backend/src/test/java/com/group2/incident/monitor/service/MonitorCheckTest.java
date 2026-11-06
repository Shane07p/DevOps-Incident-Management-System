package com.group2.incident.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.benmanes.caffeine.cache.Cache;
import com.group2.incident.common.ratelimit.RateLimitService;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-22 boundary values (D29): a monitor with a threshold of N = 3 failures in a row. Only the HTTP
 * answer is scripted; the schedule, the count, the alert through intake, and recovery run for real
 * against PostgreSQL. Outside the test transaction, because each check commits on its own.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonitorCheckTest {

	private static final ProbeOutcome DOWN = ProbeOutcome.failed(503, 12L, "Expected HTTP 200, got 503");

	private static final ProbeOutcome UP = ProbeOutcome.passed(200, 8L);

	private static final int INTERVAL_SECONDS = 60;

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private RateLimitService rateLimitService;

	@Autowired
	private MonitorWorker worker;

	@MockitoBean
	private HttpProbe probe;

	private SignedUp org;

	private int servicesCreated;

	private long serviceId;

	private long monitorId;

	// When this test's monitor is due next.
	private Instant next;

	@BeforeAll
	void createOrg() throws Exception {
		// The sign-up limit is per address, and an earlier test class may have used it up.
		((Cache<?, ?>) ReflectionTestUtils.getField(rateLimitService, "loginBuckets")).invalidateAll();
		org = orgs.signUp("monitors@monitors.test", "Monitors");
	}

	@AfterAll
	void deleteOrg() {
		if (org != null) {
			orgs.delete(org.orgId());
		}
	}

	// A service and monitor of its own for each test, so every count below is this test's alone.
	@BeforeEach
	void createMonitor() throws Exception {
		serviceId = orgs.createService(org.adminToken(), "Checkout " + ++servicesCreated, org.adminId());
		String response = mvc.perform(post("/api/monitors")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(monitorBody("https://203.0.113.10/health", 3)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		monitorId = TestOrgs.id(response);
		// Rounded up to a whole second: still due, and stored exactly (PostgreSQL keeps microseconds).
		next = Instant.parse(JsonPath.<String>read(response, "$.nextCheckAt")).truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
	}

	// Switched off afterwards, so a later test's pass checks only that test's monitor.
	@AfterEach
	void switchMonitorOff() {
		jdbc.update("UPDATE uptime_monitor SET enabled = false WHERE id = ?", monitorId);
	}

	@Test
	@DisplayName("TC-FR-22.1a: Two failures in a row raise nothing")
	void twoFailuresRaiseNothing() {
		check(DOWN);
		check(DOWN);

		assertThat(alerts()).isZero();
		assertThat(incidents()).isZero();
		assertThat(failuresInARow()).isEqualTo(2);
	}

	@Test
	@DisplayName("TC-FR-22.1b: The third failure in a row raises one alert and one incident")
	void thirdFailureRaisesOneAlertAndOneIncident() {
		check(DOWN);
		check(DOWN);
		check(DOWN);

		assertThat(alerts()).isEqualTo(1);
		assertThat(incidents()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT source FROM alert WHERE service_id = ?", String.class, serviceId))
				.isEqualTo(MonitorCheckRecorder.ALERT_SOURCE);
		assertThat(jdbc.queryForObject("SELECT active_alert_id FROM uptime_monitor WHERE id = ?", Long.class, monitorId))
				.isEqualTo(jdbc.queryForObject("SELECT id FROM alert WHERE service_id = ?", Long.class, serviceId));
	}

	@Test
	@DisplayName("TC-FR-22.1c: A fourth failure adds no second alert, and every result is kept")
	void fourthFailureAddsNoSecondAlert() {
		check(DOWN);
		check(DOWN);
		check(DOWN);
		check(DOWN);

		assertThat(alerts()).isEqualTo(1);
		assertThat(incidents()).isEqualTo(1);
		assertThat(failuresInARow()).isEqualTo(4);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monitor_check_result WHERE monitor_id = ? AND NOT success",
				Integer.class, monitorId)).isEqualTo(4);
	}

	@Test
	@DisplayName("TC-FR-22.1d: Fail, fail, pass, fail raises nothing: the count starts again")
	void passingCheckRestartsTheCount() {
		check(DOWN);
		check(DOWN);
		check(UP);
		check(DOWN);

		assertThat(alerts()).isZero();
		assertThat(failuresInARow()).isEqualTo(1);
	}

	@Test
	@DisplayName("TC-FR-22.2a: Recovery updates the alert and adds a timeline entry")
	void recoveryUpdatesTheAlertAndTheTimeline() {
		check(DOWN);
		check(DOWN);
		check(DOWN);
		Instant recoveredAt = next;
		check(UP);

		Timestamp recorded = jdbc.queryForObject("SELECT recovered_at FROM alert WHERE service_id = ?", Timestamp.class, serviceId);
		assertThat(recorded.toInstant()).isEqualTo(recoveredAt);
		assertThat(recoveryEntries()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT active_alert_id FROM uptime_monitor WHERE id = ?", Long.class, monitorId)).isNull();
		assertThat(failuresInARow()).isZero();

		// Passing again is not a second recovery.
		check(UP);
		assertThat(recoveryEntries()).isEqualTo(1);
	}

	@Test
	@DisplayName("TC-FR-22.3a: After recovery the incident stays open until a person resolves it")
	void recoveryLeavesTheIncidentOpen() {
		check(DOWN);
		check(DOWN);
		check(DOWN);
		check(UP);

		assertThat(jdbc.queryForObject("SELECT status FROM incident WHERE service_id = ?", String.class, serviceId))
				.isEqualTo("OPEN");
	}

	@Test
	@DisplayName("A monitor is not checked again before its interval has passed")
	void notCheckedBeforeItsInterval() {
		check(DOWN);

		when(probe.probe(anyString(), anyInt())).thenReturn(DOWN);
		assertThat(worker.runOnce(next.minusSeconds(1))).isZero();
		assertThat(worker.runOnce(next)).isEqualTo(1);
		verify(probe, times(2)).probe(anyString(), anyInt());
	}

	@Test
	@DisplayName("An edit saved while a check is in flight drops that check's result")
	void editDuringACheckDropsItsResult() {
		when(probe.probe(anyString(), anyInt())).thenAnswer(invocation -> {
			mvc.perform(put("/api/monitors/{id}", monitorId)
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
							.contentType(MediaType.APPLICATION_JSON)
							.content(monitorBody("https://203.0.113.20/health", 3)))
					.andExpect(status().isOk());
			return DOWN;
		});

		assertThat(worker.runOnce(next)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monitor_check_result WHERE monitor_id = ?", Integer.class, monitorId))
				.isZero();
		assertThat(failuresInARow()).isZero();
	}

	/** One check of this test's monitor at the time it is due, answering {@code outcome}. */
	private void check(ProbeOutcome outcome) {
		when(probe.probe(anyString(), anyInt())).thenReturn(outcome);
		assertThat(worker.runOnce(next)).isEqualTo(1);
		next = next.plusSeconds(INTERVAL_SECONDS);
	}

	private String monitorBody(String url, int failureThreshold) {
		return """
				{"name": "Checkout health", "url": "%s", "expectedStatus": 200, "intervalSeconds": %d,
				 "serviceId": %d, "failureThreshold": %d, "enabled": true}""".formatted(url, INTERVAL_SECONDS, serviceId, failureThreshold);
	}

	private int alerts() {
		return jdbc.queryForObject("SELECT count(*) FROM alert WHERE service_id = ?", Integer.class, serviceId);
	}

	private int incidents() {
		return jdbc.queryForObject("SELECT count(*) FROM incident WHERE service_id = ?", Integer.class, serviceId);
	}

	private int failuresInARow() {
		return jdbc.queryForObject("SELECT consecutive_failures FROM uptime_monitor WHERE id = ?", Integer.class, monitorId);
	}

	private int recoveryEntries() {
		return jdbc.queryForObject("""
				SELECT count(*) FROM timeline_entry t JOIN incident i ON i.id = t.incident_id
				WHERE i.service_id = ? AND t.event_type = 'MONITOR_RECOVERED'""", Integer.class, serviceId);
	}
}
