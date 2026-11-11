package com.group2.incident.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.benmanes.caffeine.cache.Cache;
import com.group2.incident.common.ratelimit.RateLimitService;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.heartbeat.service.HeartbeatRecorder;
import com.group2.incident.heartbeat.service.HeartbeatWorker;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEventType;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-22 boundary values (D29): heartbeats for scheduled background jobs.
 * A scheduled job pings its own URL; a late or missing ping raises an alert.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeartbeatTest {

	private static final int INTERVAL_SECONDS = 60;
	private static final int GRACE_SECONDS = 10;

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private RateLimitService rateLimitService;

	@Autowired
	private HeartbeatWorker worker;

	@Autowired
	private HeartbeatRecorder recorder;

	private SignedUp org;
	private int servicesCreated;
	private long serviceId;
	private long heartbeatId;
	private String token;
	private String pingUrl;
	private Instant lateAfter;

	@BeforeAll
	void createOrg() throws Exception {
		((Cache<?, ?>) ReflectionTestUtils.getField(rateLimitService, "loginBuckets")).invalidateAll();
		org = orgs.signUp("heartbeats@heartbeats.test", "Heartbeats");
	}

	@AfterAll
	void deleteOrg() {
		if (org != null) {
			orgs.delete(org.orgId());
		}
	}

	@BeforeEach
	void createHeartbeat() throws Exception {
		serviceId = orgs.createService(org.adminToken(), "JobService " + ++servicesCreated, org.adminId());
		String response = mvc.perform(post("/api/heartbeats")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"name": "Nightly Backup",
									"serviceId": %d,
									"intervalSeconds": %d,
									"graceSeconds": %d,
									"enabled": true
								}
								""".formatted(serviceId, INTERVAL_SECONDS, GRACE_SECONDS)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		heartbeatId = TestOrgs.id(response);
		token = JsonPath.read(response, "$.token");
		pingUrl = JsonPath.read(response, "$.pingUrl");
		lateAfter = Instant.parse(JsonPath.<String>read(response, "$.lateAfter")).truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
	}

	@AfterEach
	void switchHeartbeatOff() {
		jdbc.update("UPDATE heartbeat SET enabled = false WHERE id = ?", heartbeatId);
	}

	@Test
	@DisplayName("TC-HBa: A ping 1 second before due time plus grace raises nothing")
	void tcHBa_pingBeforeDueTimePlusGraceRaisesNothing() throws Exception {
		Instant pingTime = lateAfter.minusSeconds(1);

		// The ping lands at a chosen time, 1 second before the deadline; the URL itself is tested in
		// HeartbeatControllerTest, where the ping takes the real time.
		OrgContext.runAs(org.orgId(), () -> recorder.recordPing(heartbeatId, pingTime));

		// Check at due time plus grace: the ping moved the deadline, so nothing is late.
		worker.runOnce(lateAfter);

		assertThat(alerts()).isZero();
		assertThat(incidents()).isZero();
	}

	@Test
	@DisplayName("TC-HBb: No ping by due time plus grace raises an alert")
	void tcHBb_noPingByDueTimePlusGraceRaisesAlert() {
		// No ping sent; check runs at due time plus grace
		worker.runOnce(lateAfter);

		assertThat(alerts()).isEqualTo(1);
		assertThat(incidents()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT source FROM alert WHERE service_id = ?", String.class, serviceId))
				.isEqualTo(HeartbeatRecorder.ALERT_SOURCE);
		assertThat(jdbc.queryForObject("SELECT active_alert_id FROM heartbeat WHERE id = ?", Long.class, heartbeatId))
				.isEqualTo(jdbc.queryForObject("SELECT id FROM alert WHERE service_id = ?", Long.class, serviceId));
	}

	@Test
	@DisplayName("A subsequent check while overdue adds no second alert")
	void subsequentCheckAddsNoSecondAlert() {
		worker.runOnce(lateAfter);
		worker.runOnce(lateAfter.plusSeconds(30));

		assertThat(alerts()).isEqualTo(1);
		assertThat(incidents()).isEqualTo(1);
	}

	@Test
	@DisplayName("Recovery updates the alert and adds a timeline entry")
	void recoveryUpdatesTheAlertAndTheTimeline() throws Exception {
		worker.runOnce(lateAfter);
		assertThat(alerts()).isEqualTo(1);

		Instant recoveredAt = lateAfter.plusSeconds(20);

		// Job recovers by sending a ping
		mvc.perform(get(pingUrl))
				.andExpect(status().isOk());

		Timestamp recorded = jdbc.queryForObject("SELECT recovered_at FROM alert WHERE service_id = ?", Timestamp.class, serviceId);
		assertThat(recorded).isNotNull();
		assertThat(recoveryEntries()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT active_alert_id FROM heartbeat WHERE id = ?", Long.class, heartbeatId)).isNull();

		// Passing again is not a second recovery
		mvc.perform(post(pingUrl))
				.andExpect(status().isOk());
		assertThat(recoveryEntries()).isEqualTo(1);
	}

	@Test
	@DisplayName("After recovery the incident stays open until a person resolves it")
	void recoveryLeavesTheIncidentOpen() throws Exception {
		worker.runOnce(lateAfter);
		mvc.perform(post(pingUrl)).andExpect(status().isOk());

		String status = jdbc.queryForObject("SELECT status FROM incident WHERE service_id = ?", String.class, serviceId);
		assertThat(status).isEqualTo(IncidentStatus.OPEN.name());
	}

	private int alerts() {
		return jdbc.queryForObject("SELECT count(*) FROM alert WHERE service_id = ?", Integer.class, serviceId);
	}

	private int incidents() {
		return jdbc.queryForObject("SELECT count(*) FROM incident WHERE service_id = ?", Integer.class, serviceId);
	}

	private int recoveryEntries() {
		return jdbc.queryForObject(
				"SELECT count(*) FROM timeline_entry te JOIN incident i ON te.incident_id = i.id "
						+ "WHERE i.service_id = ? AND te.event_type = ?",
				Integer.class,
				serviceId,
				TimelineEventType.MONITOR_RECOVERED.name()
		);
	}
}
