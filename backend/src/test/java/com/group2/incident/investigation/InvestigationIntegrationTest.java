package com.group2.incident.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.deploy.entity.Deployment;
import com.group2.incident.deploy.repository.DeploymentRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class InvestigationIntegrationTest {

	private static HttpServer aiStubServer;
	private static final AtomicInteger aiStatus = new AtomicInteger(200);
	private static final AtomicReference<String> lastAiRequest = new AtomicReference<>();
	private static final AtomicReference<String> aiResponseBody = new AtomicReference<>();

	@BeforeAll
	static void startAiStub() throws IOException {
		aiStubServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		aiStubServer.createContext("/investigate", InvestigationIntegrationTest::handleAiRequest);
		aiStubServer.start();
	}

	@AfterAll
	static void stopAiStub() {
		if (aiStubServer != null) {
			aiStubServer.stop(0);
		}
	}

	@DynamicPropertySource
	static void configureAiServiceUrl(DynamicPropertyRegistry registry) {
		registry.add("app.ai.service-url", () -> "http://127.0.0.1:" + aiStubServer.getAddress().getPort());
	}

	private static void handleAiRequest(HttpExchange exchange) throws IOException {
		lastAiRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		byte[] body = aiResponseBody.get().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(aiStatus.get(), body.length);
		try (var out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	@Autowired private MockMvc mvc;
	@Autowired private TestAccounts accounts;
	@Autowired private ServiceRepository services;
	@Autowired private DeploymentRepository deployments;
	@Autowired private Clock clock;

	private String engineerToken;
	private String viewerToken;
	private Long incidentId;

	@BeforeEach
	void setUp() throws Exception {
		aiStatus.set(200);
		lastAiRequest.set(null);
		aiResponseBody.set("""
				{
					"aiGenerated": true,
					"status": "completed",
					"summary": "Checkout error spike follows deployment v1.0.1-broken.",
					"hypotheses": [
						{
							"hypothesis": "Faulty deployment v1.0.1-broken introduced regression",
							"confidence": 0.91,
							"evidenceRefs": ["ev-deploy-0"],
							"reasoning": "Deployed 10 minutes before incident"
						},
						{
							"hypothesis": "Unsupported hypothesis with missing ref",
							"confidence": 0.50,
							"evidenceRefs": ["ev-fabricated-999"],
							"reasoning": "Should be filtered out"
						}
					],
					"evidence": [
						{
							"id": "ev-deploy-0",
							"sourceTool": "query_deploy_events",
							"evidenceType": "deploy_event",
							"content": "Deploy v1.0.1-broken to checkout"
						}
					],
					"suggestedActions": [
						"Roll back checkout to v1.0.0",
						"Inspect error logs for NullPointerException"
					],
					"stepsTaken": 2,
					"tokensUsed": 320,
					"durationMs": 55.2
				}
				""");

		AppUser engineer = accounts.create("inv-engineer@test.local", Role.ENGINEER);
		accounts.create("inv-viewer@test.local", Role.VIEWER);
		engineerToken = accounts.token("inv-engineer@test.local");
		viewerToken = accounts.token("inv-viewer@test.local");

		ServiceEntity checkout = new ServiceEntity("Checkout", "Checkout service", engineer, Criticality.HIGH, clock.instant());
		checkout.putTarget(Severity.SEV2, 30, 240);
		checkout = services.saveAndFlush(checkout);

		deployments.saveAndFlush(new Deployment(
				checkout, "v1.0.1-broken", "deadbeef", "ci-bot", clock.instant().minus(10, ChronoUnit.MINUTES)));

		String created = mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Checkout 500s\",\"description\":\"Errors spiking\",\"serviceId\":"
								+ checkout.getId() + ",\"severity\":\"SEV2\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		incidentId = JsonPath.<Number>read(created, "$.id").longValue();
	}

	@Test
	void investigateStoresVerifiedResultAndGetLatestReturnsIt() throws Exception {
		String postBody = mvc.perform(post("/api/incidents/{id}/investigations", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.summary").value("Checkout error spike follows deployment v1.0.1-broken."))
				.andExpect(jsonPath("$.hypotheses", hasSize(1)))
				.andExpect(jsonPath("$.hypotheses[0].rank").value(1))
				.andExpect(jsonPath("$.hypotheses[0].title").value("Faulty deployment v1.0.1-broken introduced regression"))
				.andExpect(jsonPath("$.hypotheses[0].confidence").value(0.91))
				.andExpect(jsonPath("$.hypotheses[0].evidence", hasSize(1)))
				.andExpect(jsonPath("$.hypotheses[0].evidence[0].kind").value("deploy_event"))
				.andExpect(jsonPath("$.hypotheses[0].evidence[0].text").value("Deploy v1.0.1-broken to checkout"))
				.andExpect(jsonPath("$.suggestedActions", hasSize(2)))
				.andExpect(jsonPath("$.suggestedActions[0].text").value("Roll back checkout to v1.0.0"))
				.andExpect(jsonPath("$.suggestedActions[0].decision").value(nullValue()))
				.andExpect(jsonPath("$.suggestedActions[0].decidedBy").value(nullValue()))
				.andExpect(jsonPath("$.suggestedActions[0].decidedAt").value(nullValue()))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		// Evidence bundle sent to ai-service includes incident, timeline, and deploys.
		assertThat(lastAiRequest.get()).contains("v1.0.1-broken", "Checkout 500s", "INCIDENT_CREATED");

		// Any role (including Viewer) can fetch the latest investigation.
		Long invId = JsonPath.<Number>read(postBody, "$.id").longValue();
		mvc.perform(get("/api/incidents/{id}/investigations/latest", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(invId))
				.andExpect(jsonPath("$.hypotheses", hasSize(1)))
				.andExpect(jsonPath("$.suggestedActions", hasSize(2)));
	}

	@Test
	void aiDownReturns503AndIncidentStillLoadsUnaffected() throws Exception {
		aiStatus.set(503);

		mvc.perform(post("/api/incidents/{id}/investigations", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.message").isNotEmpty());

		// Incident still loads cleanly with no investigation stored and status unchanged.
		mvc.perform(get("/api/incidents/{id}", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.title").value("Checkout 500s"));

		mvc.perform(get("/api/incidents/{id}/investigations/latest", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isNotFound());
	}

	@Test
	void acceptAndRejectActionsAreAttributedOnTimelineAndExecuteNothing() throws Exception {
		String invBody = mvc.perform(post("/api/incidents/{id}/investigations", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		Long invId = JsonPath.<Number>read(invBody, "$.id").longValue();
		Long action1Id = JsonPath.<Number>read(invBody, "$.suggestedActions[0].id").longValue();
		Long action2Id = JsonPath.<Number>read(invBody, "$.suggestedActions[1].id").longValue();

		mvc.perform(post("/api/investigations/{id}/actions/{actionId}/decision", invId, action1Id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"decision\":\"ACCEPTED\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(action1Id))
				.andExpect(jsonPath("$.decision").value("ACCEPTED"))
				.andExpect(jsonPath("$.decidedBy").value("inv-engineer"))
				.andExpect(jsonPath("$.decidedAt").isNotEmpty());

		mvc.perform(post("/api/investigations/{id}/actions/{actionId}/decision", invId, action2Id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"decision\":\"REJECTED\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(action2Id))
				.andExpect(jsonPath("$.decision").value("REJECTED"))
				.andExpect(jsonPath("$.decidedBy").value("inv-engineer"))
				.andExpect(jsonPath("$.decidedAt").isNotEmpty());

		// Incident remains OPEN (nothing was executed) and timeline shows both decisions.
		String incidentJson = mvc.perform(get("/api/incidents/{id}", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andReturn().getResponse().getContentAsString();

		assertThat(incidentJson).contains("ACTION_ACCEPTED", "ACTION_REJECTED",
				"Accepted AI suggested action: Roll back checkout to v1.0.0",
				"Rejected AI suggested action: Inspect error logs for NullPointerException");

		// Latest investigation reflects both decisions.
		mvc.perform(get("/api/incidents/{id}/investigations/latest", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suggestedActions[0].decision").value("ACCEPTED"))
				.andExpect(jsonPath("$.suggestedActions[1].decision").value("REJECTED"));
	}

	@Test
	void viewerCannotInvestigateOrDecide() throws Exception {
		String invBody = mvc.perform(post("/api/incidents/{id}/investigations", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		Long invId = JsonPath.<Number>read(invBody, "$.id").longValue();
		Long actionId = JsonPath.<Number>read(invBody, "$.suggestedActions[0].id").longValue();

		mvc.perform(post("/api/incidents/{id}/investigations", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isForbidden());

		mvc.perform(post("/api/investigations/{id}/actions/{actionId}/decision", invId, actionId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"decision\":\"ACCEPTED\"}"))
				.andExpect(status().isForbidden());
	}
}
