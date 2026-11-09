package com.group2.incident.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concurrent duplicate webhook submissions for the same service, version, and deployedAt timestamp:
 * both must return 202 Accepted without throwing 500 errors, and exactly one row must be saved in the database
 * due to application deduplication and database-level unique constraint (V19).
 * Runs outside the test transaction against PostgreSQL.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeployConcurrencyTest {

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private SignedUp org;

	@AfterEach
	void cleanUp() {
		if (org != null) {
			orgs.delete(org.orgId());
		}
	}

	@Test
	void concurrentDuplicateDeployWebhooks_bothAcceptedAndOnlyOneSaved() throws Exception {
		org = orgs.signUp("deploy-race-" + System.currentTimeMillis() + "@race.test", "Deploy Race Org");
		orgs.createService(org.adminToken(), "PaymentGateway", org.adminId());

		String toolJson = mvc.perform(post("/api/tools")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"CI Bot\"}"))
				.andReturn().getResponse().getContentAsString();
		String toolToken = JsonPath.read(toolJson, "$.token");

		String payload = """
				{
					"service": "PaymentGateway",
					"version": "v2.0.0",
					"commit": "1122334455667788",
					"author": "CI Pipeline",
					"deployedAt": "2026-11-09T12:00:00Z"
				}
				""";

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);

			Future<MvcResult> req1 = executor.submit(() -> {
				ready.countDown();
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return mvc.perform(post("/webhooks/deploys")
								.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolToken)
								.contentType(MediaType.APPLICATION_JSON)
								.content(payload))
						.andReturn();
			});

			Future<MvcResult> req2 = executor.submit(() -> {
				ready.countDown();
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return mvc.perform(post("/webhooks/deploys")
								.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolToken)
								.contentType(MediaType.APPLICATION_JSON)
								.content(payload))
						.andReturn();
			});

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			MvcResult res1 = req1.get(10, TimeUnit.SECONDS);
			MvcResult res2 = req2.get(10, TimeUnit.SECONDS);

			// Both calls must return 202 Accepted
			assertThat(res1.getResponse().getStatus()).isEqualTo(202);
			assertThat(res2.getResponse().getStatus()).isEqualTo(202);

			String body1 = res1.getResponse().getContentAsString();
			String body2 = res2.getResponse().getContentAsString();

			String status1 = JsonPath.read(body1, "$.status");
			String status2 = JsonPath.read(body2, "$.status");

			List<String> statuses = List.of(status1, status2);
			assertThat(statuses).containsExactlyInAnyOrder("RECORDED", "DUPLICATE");

			Integer count = jdbc.queryForObject(
					"SELECT count(*) FROM deployment WHERE org_id = ? AND version = ?",
					Integer.class, org.orgId(), "v2.0.0");
			assertThat(count).isEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void concurrentDuplicateLinkDeployment_bothSucceedAndOnlyOneTimelineEntryCreated() throws Exception {
		org = orgs.signUp("link-race-" + System.currentTimeMillis() + "@race.test", "Link Race Org");
		long serviceId = orgs.createService(org.adminToken(), "PaymentGateway", org.adminId());

		String incidentJson = orgs.declareIncident(org.adminToken(), serviceId, "Concurrent Link Incident");
		long incidentId = TestOrgs.id(incidentJson);
		long initialVersion = JsonPath.<Number>read(incidentJson, "$.version").longValue();
		String initialStatus = JsonPath.read(incidentJson, "$.status");

		String toolJson = mvc.perform(post("/api/tools")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"CI Bot\"}"))
				.andReturn().getResponse().getContentAsString();
		String toolToken = JsonPath.read(toolJson, "$.token");

		String deployPayload = """
				{
					"service": "PaymentGateway",
					"version": "v1.0.0",
					"commit": "abcdef1234567890",
					"author": "CI Pipeline",
					"deployedAt": "2026-11-09T10:00:00Z"
				}
				""";
		MvcResult deployResult = mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(deployPayload))
				.andExpect(status().isAccepted())
				.andReturn();
		long deployId = JsonPath.<Number>read(deployResult.getResponse().getContentAsString(), "$.id").longValue();

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);

			Future<MvcResult> req1 = executor.submit(() -> {
				ready.countDown();
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return mvc.perform(post("/api/incidents/" + incidentId + "/deploys/" + deployId)
								.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken()))
						.andReturn();
			});

			Future<MvcResult> req2 = executor.submit(() -> {
				ready.countDown();
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return mvc.perform(post("/api/incidents/" + incidentId + "/deploys/" + deployId)
								.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken()))
						.andReturn();
			});

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			MvcResult res1 = req1.get(10, TimeUnit.SECONDS);
			MvcResult res2 = req2.get(10, TimeUnit.SECONDS);

			assertThat(res1.getResponse().getStatus()).isEqualTo(200);
			assertThat(res2.getResponse().getStatus()).isEqualTo(200);

			Integer timelineCount = jdbc.queryForObject(
					"SELECT count(*) FROM timeline_entry WHERE incident_id = ? AND event_type = 'DEPLOY_LINKED'",
					Integer.class, incidentId);
			assertThat(timelineCount).isEqualTo(1);

			Map<String, Object> incidentRow = jdbc.queryForMap(
					"SELECT status, version FROM incident WHERE id = ?", incidentId);
			assertThat(incidentRow.get("status")).isEqualTo(initialStatus);
			assertThat(((Number) incidentRow.get("version")).longValue()).isEqualTo(initialVersion);
		}
		finally {
			executor.shutdownNow();
		}
	}
}
