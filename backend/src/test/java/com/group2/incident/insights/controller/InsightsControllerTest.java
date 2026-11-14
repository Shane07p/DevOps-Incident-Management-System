package com.group2.incident.insights.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.followup.entity.FollowUp;
import com.group2.incident.followup.entity.FollowUpKind;
import com.group2.incident.followup.entity.FollowUpStatus;
import com.group2.incident.followup.repository.FollowUpRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.insights.service.InsightsService;
import com.group2.incident.notification.entity.Notification;
import com.group2.incident.notification.repository.NotificationRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class InsightsControllerTest {

	// Reference fixed instant: Friday 2026-03-20T12:00:00Z (17:30 IST)
	private static final Instant NOW = Instant.parse("2026-03-20T12:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private FollowUpRepository followUps;

	@Autowired
	private NotificationRepository notifications;

	@Autowired
	private InsightsService insightsService;

	@Autowired
	private Clock clock;

	private AppUser admin;
	private AppUser engineer;
	private AppUser viewer;

	private String adminToken;
	private String engineerToken;
	private String viewerToken;

	private ServiceEntity service;

	@BeforeEach
	void setUp() throws Exception {
		holdClockAt(NOW);

		admin = accounts.create("insights-admin@test.local", Role.ADMIN);
		engineer = accounts.create("insights-eng@test.local", Role.ENGINEER);
		viewer = accounts.create("insights-viewer@test.local", Role.VIEWER);

		adminToken = accounts.token("insights-admin@test.local");
		engineerToken = accounts.token("insights-eng@test.local");
		viewerToken = accounts.token("insights-viewer@test.local");

		ServiceEntity unsaved = new ServiceEntity("Insights Payments", "Payments service", admin, Criticality.HIGH, NOW);
		for (Severity severity : Severity.values()) {
			unsaved.putTarget(severity, 15, 120);
		}
		service = services.saveAndFlush(unsaved);
	}

	@AfterEach
	void tearDown() {
		ReflectionTestUtils.setField(insightsService, "clock", clock);
	}

	private void holdClockAt(Instant instant) {
		ReflectionTestUtils.setField(insightsService, "clock", Clock.fixed(instant, ZoneOffset.UTC));
	}

	@ParameterizedTest
	@ValueSource(ints = { 7, 30, 90 })
	@DisplayName("GET /api/insights?days=7|30|90 returns 200 with complete response structure")
	void validDays_returns200WithCompleteStructure(int days) throws Exception {
		mvc.perform(get("/api/insights")
						.queryParam("days", String.valueOf(days))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.from").isNotEmpty())
				.andExpect(jsonPath("$.to").isNotEmpty())
				.andExpect(jsonPath("$.incidents.total").isNumber())
				.andExpect(jsonPath("$.incidents.open").isNumber())
				.andExpect(jsonPath("$.incidents.resolved").isNumber())
				.andExpect(jsonPath("$.mtta").isMap())
				.andExpect(jsonPath("$.mttr").isMap())
				.andExpect(jsonPath("$.bySeverity").isArray())
				.andExpect(jsonPath("$.byService").isArray())
				.andExpect(jsonPath("$.slaBreachRate").isMap())
				.andExpect(jsonPath("$.followUps.total").isNumber())
				.andExpect(jsonPath("$.followUps.done").isNumber())
				.andExpect(jsonPath("$.followUps.overdue").isNumber())
				.andExpect(jsonPath("$.outOfHoursPages").isNumber());
	}

	@Test
	@DisplayName("GET /api/insights without days param defaults to 30 days")
	void defaultDays_defaultsTo30() throws Exception {
		mvc.perform(get("/api/insights")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.to").value(NOW.toString()))
				.andExpect(jsonPath("$.from").value(NOW.minus(Duration.ofDays(30)).toString()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "1", "14", "60", "365", "0", "-7", "abc" })
	@DisplayName("Invalid days param returns 400 Bad Request")
	void invalidDays_returns400(String invalidDays) throws Exception {
		mvc.perform(get("/api/insights")
						.queryParam("days", invalidDays)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Role access: ADMIN, ENGINEER, and VIEWER are permitted")
	void roleAccess_permittedRoles() throws Exception {
		mvc.perform(get("/api/insights")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk());

		mvc.perform(get("/api/insights")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
				.andExpect(status().isOk());

		mvc.perform(get("/api/insights")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("Unauthenticated request returns 401 Unauthorized")
	void unauthenticated_returns401() throws Exception {
		mvc.perform(get("/api/insights"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("End-to-end integration: aggregates incidents, SLA breach rates, follow-ups, and out-of-hours pages")
	void endToEndAggregations() throws Exception {
		// Incident 1: created 2 hours ago.
		// Acknowledged 30 min later -> breached response target (15 min)!
		// Resolved 60 min later (total 90 min) -> met resolution target (120 min)!
		Instant created1 = NOW.minus(Duration.ofHours(2));
		Incident inc1 = new Incident("Checkout down", "Failure in checkout", service, Severity.SEV1,
				IncidentStatus.OPEN, admin, created1, 15, 120);
		inc1.assignNumber(1001L);
		inc1.acknowledge(engineer, created1.plus(Duration.ofMinutes(30)));
		inc1.transitionTo(IncidentStatus.RESOLVED, created1.plus(Duration.ofMinutes(90)));
		incidents.saveAndFlush(inc1);

		// Incident 2: created 30 minutes ago.
		// Acknowledged 10 min later -> met response target (15 min)!
		// Unresolved, but only 30 min elapsed <= 120 min target -> in-flight, excluded from resolution denominator!
		Instant created2 = NOW.minus(Duration.ofMinutes(30));
		Incident inc2 = new Incident("Slow queries", "DB high latency", service, Severity.SEV2,
				IncidentStatus.OPEN, admin, created2, 15, 120);
		inc2.assignNumber(1002L);
		inc2.acknowledge(engineer, created2.plus(Duration.ofMinutes(10)));
		incidents.saveAndFlush(inc2);

		// Follow-ups:
		// Today in UTC is 2026-03-20
		LocalDate today = LocalDate.of(2026, 3, 20);
		// FU 1: created in window, marked DONE
		FollowUp fu1 = new FollowUp(inc1, "Add connection pool alarm", FollowUpKind.CORRECTIVE, engineer,
				today.minusDays(2), created1.plusSeconds(60));
		fu1.moveTo(FollowUpStatus.DONE, NOW);
		followUps.saveAndFlush(fu1);

		// FU 2: created in window, OPEN, due 2 days ago -> OVERDUE
		FollowUp fu2 = new FollowUp(inc1, "Tune slow query", FollowUpKind.PREVENTIVE, engineer,
				today.minusDays(2), created1.plusSeconds(120));
		followUps.saveAndFlush(fu2);

		// Notifications:
		// Notification 1: attached to inc1, sent at Saturday 10:00 UTC (Saturday afternoon IST -> OUT OF HOURS)
		Notification page1 = new Notification(engineer, inc1, "Page for checkout", Instant.parse("2026-03-14T10:00:00Z"));
		notifications.saveAndFlush(page1);

		// Notification 2: attached to inc2, sent at Wednesday 14:00 IST (08:30 UTC -> IN HOURS)
		Notification page2 = new Notification(engineer, inc2, "In hours alert", Instant.parse("2026-03-18T08:30:00Z"));
		notifications.saveAndFlush(page2);

		mvc.perform(get("/api/insights")
						.queryParam("days", "7")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidents.total").value(2))
				.andExpect(jsonPath("$.incidents.open").value(1))
				.andExpect(jsonPath("$.incidents.resolved").value(1))
				// MTTA: 30 min and 10 min -> sorted: 10.0, 30.0 -> mean: 20.0, median: 20.0, p90: 30.0
				.andExpect(jsonPath("$.mtta.mean").value(20.0))
				.andExpect(jsonPath("$.mtta.median").value(20.0))
				.andExpect(jsonPath("$.mtta.p90").value(30.0))
				// MTTR: only inc1 (90 min) -> mean: 90.0, median: 90.0, p90: 90.0
				.andExpect(jsonPath("$.mttr.mean").value(90.0))
				.andExpect(jsonPath("$.mttr.median").value(90.0))
				.andExpect(jsonPath("$.mttr.p90").value(90.0))
				// SLA breach rate:
				// Response: 2 measurable (30m breached, 10m met) -> 1 / 2 = 0.500
				.andExpect(jsonPath("$.slaBreachRate.response").value(0.5))
				// Resolution: 1 measurable (inc1 met; inc2 in-flight) -> 0 / 1 = 0.000
				.andExpect(jsonPath("$.slaBreachRate.resolution").value(0.0))
				// Follow-ups: total 2, done 1, overdue 1, completionRate = 0.500
				.andExpect(jsonPath("$.followUps.total").value(2))
				.andExpect(jsonPath("$.followUps.done").value(1))
				.andExpect(jsonPath("$.followUps.overdue").value(1))
				.andExpect(jsonPath("$.followUps.completionRate").value(0.5))
				// Out of hours pages: 1 out of hours page
				.andExpect(jsonPath("$.outOfHoursPages").value(1));
	}
}
