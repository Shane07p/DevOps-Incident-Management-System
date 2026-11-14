package com.group2.incident.investigation;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.group2.incident.incident.repository.TimelineEntryRepository;
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
class DraftIntegrationTest {

	private static HttpServer aiStubServer;
	private static final AtomicInteger aiStatus = new AtomicInteger(200);
	private static final AtomicReference<String> lastAiRequest = new AtomicReference<>();
	private static final AtomicReference<String> aiResponseBody = new AtomicReference<>();

	@BeforeAll
	static void startAiStub() throws IOException {
		aiStubServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		aiStubServer.createContext("/draft", DraftIntegrationTest::handleAiRequest);
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
	@Autowired private TimelineEntryRepository timelineEntries;
	@Autowired private Clock clock;

	private String engineerToken;
	private String viewerToken;
	private Long incidentId;
	private String incidentRef;
	private Long timelineEntryId;

	@BeforeEach
	void setUp() throws Exception {
		aiStatus.set(200);
		lastAiRequest.set(null);

		AppUser engineer = accounts.create("draft-engineer@test.local", Role.ENGINEER);
		accounts.create("draft-viewer@test.local", Role.VIEWER);
		engineerToken = accounts.token("draft-engineer@test.local");
		viewerToken = accounts.token("draft-viewer@test.local");

		ServiceEntity payments = new ServiceEntity("Payments", "Payments service", engineer, Criticality.HIGH, clock.instant());
		payments.putTarget(Severity.SEV2, 30, 240);
		payments = services.saveAndFlush(payments);

		String created = mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Payment timeout\",\"description\":\"Gateway latency spike\",\"serviceId\":"
								+ payments.getId() + ",\"severity\":\"SEV2\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		incidentId = JsonPath.<Number>read(created, "$.id").longValue();
		incidentRef = JsonPath.read(created, "$.reference");
		timelineEntryId = JsonPath.<Number>read(created, "$.timeline[0].id").longValue();
	}

	@Test
	void eachDraftKindReturnsItsExpectedShapeWithoutSavingAnything() throws Exception {
		int timelineCountBefore = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId).size();

		// 1. SUMMARY
		aiResponseBody.set("""
				{"aiGenerated":true,"kind":"SUMMARY","text":"Summary of %s (entry #%d).","postmortem":null}
				""".formatted(incidentRef, timelineEntryId));

		mvc.perform(post("/api/incidents/{id}/drafts", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"SUMMARY\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("SUMMARY"))
				.andExpect(jsonPath("$.text").value("Summary of " + incidentRef + " (entry #" + timelineEntryId + ")."))
				.andExpect(jsonPath("$.postmortem").value(nullValue()))
				.andExpect(jsonPath("$.generatedAt").isNotEmpty());

		// 2. STATUS_UPDATE
		aiResponseBody.set("""
				{"aiGenerated":true,"kind":"STATUS_UPDATE","text":"Investigating %s on Payments.","postmortem":null}
				""".formatted(incidentRef));

		mvc.perform(post("/api/incidents/{id}/drafts", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"STATUS_UPDATE\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("STATUS_UPDATE"))
				.andExpect(jsonPath("$.text").value("Investigating " + incidentRef + " on Payments."))
				.andExpect(jsonPath("$.postmortem").value(nullValue()))
				.andExpect(jsonPath("$.generatedAt").isNotEmpty());

		// 3. POSTMORTEM
		aiResponseBody.set("""
				{
					"aiGenerated": true,
					"kind": "POSTMORTEM",
					"text": "Postmortem overview for %s.",
					"postmortem": {
						"summary": "Latency issue on %s.",
						"impact": "Payments delayed.",
						"rootCause": "Gateway thread pool exhaustion.",
						"resolution": "Increased pool size.",
						"wentWell": "Alert fired promptly.",
						"wentBadly": "No circuit breaker configured."
					}
				}
				""".formatted(incidentRef, incidentRef));

		mvc.perform(post("/api/incidents/{id}/drafts", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"POSTMORTEM\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("POSTMORTEM"))
				.andExpect(jsonPath("$.text").value("Postmortem overview for " + incidentRef + "."))
				.andExpect(jsonPath("$.postmortem.summary").value("Latency issue on " + incidentRef + "."))
				.andExpect(jsonPath("$.postmortem.impact").value("Payments delayed."))
				.andExpect(jsonPath("$.postmortem.rootCause").value("Gateway thread pool exhaustion."))
				.andExpect(jsonPath("$.postmortem.resolution").value("Increased pool size."))
				.andExpect(jsonPath("$.postmortem.wentWell").value("Alert fired promptly."))
				.andExpect(jsonPath("$.postmortem.wentBadly").value("No circuit breaker configured."))
				.andExpect(jsonPath("$.generatedAt").isNotEmpty());

		// Nothing is saved to the incident timeline.
		assertThat(timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId))
				.hasSize(timelineCountBefore);
	}

	@Test
	void inventedIncidentAndEntryReferencesAreRemoved() throws Exception {
		aiResponseBody.set("""
				{
					"aiGenerated": true,
					"kind": "POSTMORTEM",
					"text": "%s mirrors INC-99999 and entry #88888, while entry #%d is real.",
					"postmortem": {
						"summary": "Real %s, fake INC-99999.",
						"impact": "See entry #88888.",
						"rootCause": "Timeout.",
						"resolution": "Mitigated.",
						"wentWell": "Detected quickly.",
						"wentBadly": "Lacked alert."
					}
				}
				""".formatted(incidentRef, timelineEntryId, incidentRef));

		String response = mvc.perform(post("/api/incidents/{id}/drafts", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"POSTMORTEM\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		String text = JsonPath.read(response, "$.text");
		String pmSummary = JsonPath.read(response, "$.postmortem.summary");
		String pmImpact = JsonPath.read(response, "$.postmortem.impact");

		assertThat(text).contains(incidentRef, "entry #" + timelineEntryId);
		assertThat(text).doesNotContain("INC-99999", "88888");
		assertThat(pmSummary).contains(incidentRef).doesNotContain("INC-99999");
		assertThat(pmImpact).doesNotContain("88888");
	}

	@Test
	void aiDownReturns503AndIncidentStillWorks() throws Exception {
		aiStatus.set(503);

		mvc.perform(post("/api/incidents/{id}/drafts", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"SUMMARY\"}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value(503));

		mvc.perform(get("/api/incidents/{id}", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Payment timeout"));
	}

	@Test
	void viewerGets403() throws Exception {
		mvc.perform(post("/api/incidents/{id}/drafts", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"SUMMARY\"}"))
				.andExpect(status().isForbidden());
	}
}
