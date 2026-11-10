package com.group2.incident.statuspage.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.benmanes.caffeine.cache.Cache;
import com.group2.incident.common.ratelimit.RateLimitService;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.monitor.repository.DueMonitor;
import com.group2.incident.monitor.service.MonitorCheckRecorder;
import com.group2.incident.monitor.service.ProbeOutcome;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public status page and its updates (#160, D45, FR-21), through the API with real orgs. No test
 * transaction: the page is read as the org its slug names, in a transaction of its own, as in production.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PublicStatusTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private RateLimitService rateLimitService;

	@Autowired
	private Clock clock;

	@Autowired
	private MonitorCheckRecorder recorder;

	private final List<Long> createdOrgs = new ArrayList<>();

	@AfterEach
	void deleteOrgs() {
		createdOrgs.forEach(orgs::delete);
	}

	@Test
	void unknownSlugIsNotFound() throws Exception {
		mvc.perform(get("/api/public/status/no-such-org-" + UUID.randomUUID())).andExpect(status().isNotFound());
	}

	@Test
	void theCustomerSeesOnlyPublishedServicesWithHealthAndNinetyDayUptime() throws Exception {
		SignedUp org = signUp();
		long payments = orgs.createService(org.adminToken(), "payments-api-prod", org.adminId());
		orgs.createService(org.adminToken(), "internal-batch", org.adminId());
		publish(org, payments, "payments-api-prod", "Payments");
		long monitor = TestOrgs.id(send(org.adminToken(), post("/api/monitors"), """
				{"name": "Health", "url": "https://203.0.113.10/health", "expectedStatus": 200, "intervalSeconds": 60,
				 "serviceId": %d, "failureThreshold": 3, "enabled": true}""".formatted(payments), 201));
		// One failure below the threshold of three: degraded, not down.
		jdbc.update("update uptime_monitor set last_checked_at = ?, consecutive_failures = 1 where id = ?",
				Timestamp.from(clock.instant()), monitor);

		Instant today = clock.instant().truncatedTo(ChronoUnit.DAYS);
		check(org, monitor, today.plusSeconds(1), true);
		check(org, monitor, today.plusSeconds(2), true);
		check(org, monitor, today.plusSeconds(3), false);
		check(org, monitor, today.minus(Duration.ofDays(10)).plusSeconds(3600), true);
		check(org, monitor, today.minus(Duration.ofDays(100)), false);

		String page = page(org);

		assertThat(JsonPath.<List<String>>read(page, "$.services[*].name")).containsExactly("Payments");
		assertThat(page).doesNotContain("payments-api-prod").doesNotContain("internal-batch");
		assertThat(JsonPath.<String>read(page, "$.services[0].health")).isEqualTo("DEGRADED");
		// 3 of the 4 checks inside the last 90 days passed; the one 100 days ago does not count.
		assertThat(JsonPath.<Double>read(page, "$.services[0].uptimePercent")).isEqualTo(75.0);
		List<String> dates = JsonPath.read(page, "$.services[0].uptimeDays[*].date");
		assertThat(dates).hasSize(90);
		assertThat(dates.getLast()).isEqualTo(LocalDate.ofInstant(today, ZoneOffset.UTC).toString());
		assertThat(JsonPath.<Double>read(page, "$.services[0].uptimeDays[89].uptimePercent")).isEqualTo(66.67);
		assertThat(JsonPath.<Double>read(page, "$.services[0].uptimeDays[79].uptimePercent")).isEqualTo(100.0);
		assertThat(JsonPath.<Object>read(page, "$.services[0].uptimeDays[0].uptimePercent")).isNull();
	}

	@Test
	void nothingInternalReachesThePublicPage() throws Exception {
		SignedUp org = signUp();
		long service = orgs.createService(org.adminToken(), "Checkout", org.adminId());
		publish(org, service, "Checkout", "Checkout");
		long comms = addMember(org, "Quentin Internalname", "ENGINEER");
		String commsToken = login("Quentin Internalname");
		String incident = orgs.declareIncident(org.adminToken(), service, "Primary db-7 on 10.1.2.3 is out of disk");
		long incidentId = TestOrgs.id(incident);
		incident = assignRole(org, incidentId, "COMMS", comms, JsonPath.<Number>read(incident, "$.version").longValue());
		send(org.adminToken(), post("/api/incidents/{id}/comments", incidentId), "{\"comment\":\"Asha is restarting the replica\"}", 201);

		send(commsToken, post("/api/incidents/{id}/public-updates", incidentId),
				"{\"status\":\"INVESTIGATING\",\"message\":\"Some payments are slow; we are looking into it.\"}", 201);

		String page = page(org);
		assertThat(page).contains("Some payments are slow; we are looking into it.")
				.doesNotContain("db-7").doesNotContain("10.1.2.3").doesNotContain("Test incident")
				.doesNotContain("Asha").doesNotContain("Quentin").doesNotContain("Internalname")
				.doesNotContain("@").doesNotContain("INC-");
		// The team still sees who published it, on the internal timeline.
		String internal = send(org.adminToken(), get("/api/incidents/{id}", incidentId), null, 200);
		assertThat(JsonPath.<List<String>>read(internal, "$.timeline[?(@.eventType == 'PUBLIC_UPDATE')].actor"))
				.containsExactly("Quentin Internalname");
	}

	/** TC-FR-21.2a: the incident's Lead, its Comms, and an Admin publish; another Engineer and a Viewer cannot. */
	@Test
	void onlyTheLeadCommsOrAnAdminPublishes() throws Exception {
		SignedUp org = signUp();
		long service = orgs.createService(org.adminToken(), "Search", org.adminId());
		publish(org, service, "Search", "Search");
		long lead = addMember(org, "Lena Lead", "ENGINEER");
		long comms = addMember(org, "Cora Comms", "ENGINEER");
		addMember(org, "Otto Other", "ENGINEER");
		addMember(org, "Vera Viewer", "VIEWER");
		String incident = orgs.declareIncident(org.adminToken(), service, "Search is down");
		long incidentId = TestOrgs.id(incident);
		incident = send(org.adminToken(), put("/api/incidents/{id}/assignee", incidentId),
				"{\"assigneeId\": %d, \"expectedVersion\": %d}".formatted(lead, JsonPath.<Number>read(incident, "$.version").longValue()), 200);
		assignRole(org, incidentId, "COMMS", comms, JsonPath.<Number>read(incident, "$.version").longValue());

		String update = "{\"status\":\"IDENTIFIED\",\"message\":\"We found the cause.\"}";
		send(login("Lena Lead"), post("/api/incidents/{id}/public-updates", incidentId), update, 201);
		send(login("Cora Comms"), post("/api/incidents/{id}/public-updates", incidentId), update, 201);
		send(org.adminToken(), post("/api/incidents/{id}/public-updates", incidentId), update, 201);
		send(login("Otto Other"), post("/api/incidents/{id}/public-updates", incidentId), update, 403);
		send(login("Vera Viewer"), post("/api/incidents/{id}/public-updates", incidentId), update, 403);

		String internal = send(org.adminToken(), get("/api/incidents/{id}", incidentId), null, 200);
		assertThat(JsonPath.<List<String>>read(internal, "$.timeline[?(@.eventType == 'PUBLIC_UPDATE')].actor"))
				.containsExactly("Lena Lead", "Cora Comms", "Admin");
		assertThat(JsonPath.<List<Object>>read(page(org), "$.activeIncidents[0].updates")).hasSize(3);
	}

	@Test
	void aResolvedUpdateMovesTheIncidentToResolvedAndAnotherOrgNeverAppears() throws Exception {
		SignedUp a = signUp();
		SignedUp b = signUp();
		long serviceOfA = orgs.createService(a.adminToken(), "Checkout", a.adminId());
		publish(a, serviceOfA, "Checkout", "Checkout");
		long serviceOfB = orgs.createService(b.adminToken(), "Checkout", b.adminId());
		publish(b, serviceOfB, "Checkout", "Bravo Checkout");

		long fixed = TestOrgs.id(orgs.declareIncident(a.adminToken(), serviceOfA, "Card payments failing"));
		long ongoing = TestOrgs.id(orgs.declareIncident(a.adminToken(), serviceOfA, "Slow receipts"));
		long ofB = TestOrgs.id(orgs.declareIncident(b.adminToken(), serviceOfB, "Bravo outage"));
		postUpdate(a, fixed, "INVESTIGATING", "Card payments are failing.");
		postUpdate(a, ongoing, "MONITORING", "Receipts are delayed.");
		postUpdate(a, fixed, "RESOLVED", "Card payments work again.");
		postUpdate(b, ofB, "INVESTIGATING", "Bravo message.");

		String pageOfA = page(a);
		assertThat(JsonPath.<List<Number>>read(pageOfA, "$.activeIncidents[*].id")).extracting(Number::longValue)
				.containsExactly(ongoing);
		assertThat(JsonPath.<List<Number>>read(pageOfA, "$.resolvedIncidents[*].id")).extracting(Number::longValue)
				.containsExactly(fixed);
		assertThat(JsonPath.<String>read(pageOfA, "$.resolvedIncidents[0].resolvedAt")).isNotNull();
		assertThat(JsonPath.<String>read(pageOfA, "$.resolvedIncidents[0].serviceName")).isEqualTo("Checkout");
		assertThat(JsonPath.<List<String>>read(pageOfA, "$.resolvedIncidents[0].updates[*].status"))
				.containsExactly("RESOLVED", "INVESTIGATING");
		assertThat(JsonPath.<Object>read(pageOfA, "$.activeIncidents[0].resolvedAt")).isNull();
		assertThat(pageOfA).doesNotContain("Bravo");

		String pageOfB = page(b);
		assertThat(JsonPath.<List<String>>read(pageOfB, "$.services[*].name")).containsExactly("Bravo Checkout");
		assertThat(pageOfB).contains("Bravo message.").doesNotContain("Card payments").doesNotContain("Receipts");
	}

	@Test
	void anUpdateOnAServiceOffThePageIsRefused() throws Exception {
		SignedUp org = signUp();
		long hidden = orgs.createService(org.adminToken(), "Ledger", org.adminId());
		long incidentId = TestOrgs.id(orgs.declareIncident(org.adminToken(), hidden, "Ledger lag"));

		send(org.adminToken(), post("/api/incidents/{id}/public-updates", incidentId),
				"{\"status\":\"INVESTIGATING\",\"message\":\"x\"}", 409);
		send(org.adminToken(), post("/api/incidents/{id}/public-updates", incidentId),
				"{\"status\":\"SOLVED\",\"message\":\"x\"}", 400);
		send(org.adminToken(), post("/api/incidents/{id}/public-updates", incidentId),
				"{\"status\":\"INVESTIGATING\",\"message\":\"  \"}", 400);
	}

	@Test
	void aMovedMonitorLeavesItsEarlierChecksWithTheServiceItWatched() throws Exception {
		SignedUp org = signUp();
		long first = orgs.createService(org.adminToken(), "Checkout", org.adminId());
		long second = orgs.createService(org.adminToken(), "Search", org.adminId());
		publish(org, first, "Checkout", "Checkout");
		publish(org, second, "Search", "Search");
		String monitorBody = """
				{"name": "Health", "url": "https://203.0.113.10/health", "expectedStatus": 200, "intervalSeconds": 60,
				 "serviceId": %d, "failureThreshold": 3, "enabled": true}""";
		long monitor = TestOrgs.id(send(org.adminToken(), post("/api/monitors"), monitorBody.formatted(first), 201));

		record(org, monitor, ProbeOutcome.passed(200, 12));
		send(org.adminToken(), put("/api/monitors/{id}", monitor), monitorBody.formatted(second), 200);
		record(org, monitor, ProbeOutcome.failed(500, 12L, "HTTP 500"));

		String page = page(org);
		assertThat(JsonPath.<List<Double>>read(page, "$.services[?(@.name == 'Checkout')].uptimePercent"))
				.containsExactly(100.0);
		assertThat(JsonPath.<List<Double>>read(page, "$.services[?(@.name == 'Search')].uptimePercent"))
				.containsExactly(0.0);
	}

	@Test
	void aFollowUpAfterResolvingKeepsTheResolutionTime() throws Exception {
		SignedUp org = signUp();
		long service = orgs.createService(org.adminToken(), "Checkout", org.adminId());
		publish(org, service, "Checkout", "Checkout");
		long incidentId = TestOrgs.id(orgs.declareIncident(org.adminToken(), service, "Checkout failing"));

		postUpdate(org, incidentId, "INVESTIGATING", "Checkout is failing.");
		String resolved = send(org.adminToken(), post("/api/incidents/{id}/public-updates", incidentId),
				"{\"status\":\"RESOLVED\",\"message\":\"Checkout works again.\"}", 201);
		postUpdate(org, incidentId, "RESOLVED", "The cause was an expired certificate.");

		String page = page(org);
		assertThat(Instant.parse(JsonPath.read(page, "$.resolvedIncidents[0].resolvedAt")))
				.isEqualTo(Instant.parse(JsonPath.read(resolved, "$.createdAt")));
		assertThat(JsonPath.<List<Object>>read(page, "$.resolvedIncidents[0].updates")).hasSize(3);
	}

	@Test
	void aRecentFollowUpDoesNotBringBackAnIncidentResolvedLongAgo() throws Exception {
		SignedUp org = signUp();
		long service = orgs.createService(org.adminToken(), "Checkout", org.adminId());
		publish(org, service, "Checkout", "Checkout");
		long incidentId = TestOrgs.id(orgs.declareIncident(org.adminToken(), service, "Checkout failing"));
		Instant now = clock.instant();
		update(org, incidentId, "INVESTIGATING", now.minus(Duration.ofDays(100)));
		update(org, incidentId, "RESOLVED", now.minus(Duration.ofDays(95)));
		update(org, incidentId, "RESOLVED", now.minus(Duration.ofDays(1)));

		String page = page(org);
		assertThat(JsonPath.<List<Object>>read(page, "$.resolvedIncidents")).isEmpty();
		assertThat(JsonPath.<List<Object>>read(page, "$.activeIncidents")).isEmpty();
	}

	@Test
	void aStaleTokenDoesNotStopTheCustomerReadingThePage() throws Exception {
		SignedUp org = signUp();
		mvc.perform(get("/api/public/status/{slug}", slug(org)).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
				.andExpect(status().isOk());
	}

	private SignedUp signUp() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		SignedUp org = orgs.signUp("status-" + suffix + "@status.test", "Status " + suffix);
		createdOrgs.add(org.orgId());
		return org;
	}

	private String slug(SignedUp org) {
		return jdbc.queryForObject("select slug from organization where id = ?", String.class, org.orgId());
	}

	private String page(SignedUp org) throws Exception {
		return mvc.perform(get("/api/public/status/{slug}", slug(org)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private void publish(SignedUp org, long serviceId, String name, String publicName) throws Exception {
		String target = "{\"responseMinutes\": 15, \"resolutionMinutes\": 240}";
		send(org.adminToken(), put("/api/services/{id}", serviceId), """
				{"name": "%s", "description": "Test service", "ownerId": %d, "criticality": "HIGH",
				 "targets": {"SEV1": %s, "SEV2": %s, "SEV3": %s, "SEV4": %s},
				 "publicVisible": true, "publicName": "%s"}"""
				.formatted(name, org.adminId(), target, target, target, target, publicName), 200);
	}

	private void check(SignedUp org, long monitorId, Instant at, boolean success) {
		jdbc.update("""
				insert into monitor_check_result (org_id, monitor_id, service_id, checked_at, success)
				select ?, id, service_id, ?, ? from uptime_monitor where id = ?""",
				org.orgId(), Timestamp.from(at), success, monitorId);
	}

	/** A check through the recorder the monitor worker uses, with the monitor's current version. */
	private void record(SignedUp org, long monitorId, ProbeOutcome outcome) {
		long version = jdbc.queryForObject("select version from uptime_monitor where id = ?", Long.class, monitorId);
		boolean saved = OrgContext.runAs(org.orgId(), () -> recorder.record(
				new DueMonitor(monitorId, org.orgId(), version, "https://203.0.113.10/health", 200), outcome, clock.instant()));
		assertThat(saved).isTrue();
	}

	/** A public update written at a chosen time, as the API cannot backdate one. */
	private void update(SignedUp org, long incidentId, String status, Instant at) {
		jdbc.update("""
				insert into public_update (org_id, incident_id, status, message, author_id, created_at)
				values (?, ?, ?, ?, ?, ?)""", org.orgId(), incidentId, status, "Update " + status, org.adminId(), Timestamp.from(at));
	}

	private long addMember(SignedUp org, String displayName, String role) throws Exception {
		return TestOrgs.id(send(org.adminToken(), post("/api/users"), """
				{"email": "%s", "displayName": "%s", "password": "%s", "role": "%s"}"""
				.formatted(email(displayName), displayName, TestOrgs.PASSWORD, role), 201));
	}

	private String login(String displayName) throws Exception {
		// The login limit is per address, and these tests log in several people.
		((Cache<?, ?>) ReflectionTestUtils.getField(rateLimitService, "loginBuckets")).invalidateAll();
		String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email(displayName), TestOrgs.PASSWORD)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.token");
	}

	private String email(String displayName) {
		return displayName.toLowerCase().replace(' ', '.') + "-" + createdOrgs.getLast() + "@status.test";
	}

	private String assignRole(SignedUp org, long incidentId, String role, long userId, long version) throws Exception {
		return send(org.adminToken(), put("/api/incidents/{id}/roles", incidentId),
				"{\"role\":\"%s\",\"userId\": %d, \"expectedVersion\": %d}".formatted(role, userId, version), 200);
	}

	private void postUpdate(SignedUp org, long incidentId, String status, String message) throws Exception {
		send(org.adminToken(), post("/api/incidents/{id}/public-updates", incidentId),
				"{\"status\":\"%s\",\"message\":\"%s\"}".formatted(status, message), 201);
	}

	private String send(String token, MockHttpServletRequestBuilder request, String body, int expectedStatus) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
	}
}
