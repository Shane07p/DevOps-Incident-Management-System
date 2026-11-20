package com.group2.incident.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.investigation.entity.ActionDecision;
import com.group2.incident.investigation.entity.Investigation;
import com.group2.incident.investigation.entity.InvestigationAction;
import com.group2.incident.investigation.entity.InvestigationHypothesis;
import com.group2.incident.investigation.repository.InvestigationRepository;
import com.group2.incident.org.service.OrgMembers;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.group2.incident.support.TestOrgs;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
	@Autowired private TestOrgs testOrgs;
	@Autowired private IncidentRepository incidents;
	@Autowired private InvestigationRepository investigations;
	@Autowired private OrgMembers members;
	@Autowired private TimelineEntryRepository timelineEntries;
	@Autowired private TransactionTemplate transactionTemplate;
	@Autowired private Clock clock;

	private TestOrgs.SignedUp org;
	private String engineerToken;
	private String viewerToken;
	private Long incidentId;
	private String incidentRef;
	private Long timelineEntryId;

	@BeforeEach
	void setUp() throws Exception {
		aiStatus.set(200);
		lastAiRequest.set(null);

		String suffix = UUID.randomUUID().toString().replace("-", "");
		org = testOrgs.signUp("draft-admin-" + suffix + "@test.local", "Draft " + suffix);
		String engineerEmail = "draft-engineer-" + suffix + "@test.local";
		String viewerEmail = "draft-viewer-" + suffix + "@test.local";
		for (var user : List.of(Map.entry(engineerEmail, "ENGINEER"), Map.entry(viewerEmail, "VIEWER"))) {
			mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","displayName":"Draft user","password":"%s","role":"%s"}
								""".formatted(user.getKey(), TestOrgs.PASSWORD, user.getValue())))
					.andExpect(status().isCreated());
		}
		engineerToken = accounts.token(engineerEmail, TestOrgs.PASSWORD);
		viewerToken = accounts.token(viewerEmail, TestOrgs.PASSWORD);
		long serviceId = testOrgs.createService(org.adminToken(), "Payments", org.adminId());

		String created = mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Payment timeout\",\"description\":\"Gateway latency spike\",\"serviceId\":"
								+ serviceId + ",\"severity\":\"SEV2\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		incidentId = JsonPath.<Number>read(created, "$.id").longValue();
		incidentRef = JsonPath.read(created, "$.reference");
		timelineEntryId = JsonPath.<Number>read(created, "$.timeline[0].id").longValue();
	}

	@AfterEach
	void cleanUp() {
		if (org != null) {
			testOrgs.delete(org.orgId());
			org = null;
		}
	}

	private int timelineCount() {
		return OrgContext.runAs(org.orgId(), () -> transactionTemplate.execute(status ->
				timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId).size()));
	}

	@Test
	void eachDraftKindReturnsItsExpectedShapeWithoutSavingAnything() throws Exception {
		int timelineCountBefore = timelineCount();

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
		assertThat(timelineCount()).isEqualTo(timelineCountBefore);
		assertThat(JsonPath.<List<?>>read(lastAiRequest.get(), "$.hypotheses")).isEmpty();
		assertThat(JsonPath.<List<?>>read(lastAiRequest.get(), "$.followUps")).isEmpty();
		assertThat(JsonPath.<List<?>>read(lastAiRequest.get(), "$.acceptedActions")).isEmpty();
	}

	@Test
	void sendsRealFollowUpsAndLatestHypothesesSeparatelyFromAcceptedActions() throws Exception {
		mvc.perform(post("/api/incidents/{id}/follow-ups", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Add smoke check","kind":"PREVENTIVE","ownerId":%d,"dueDate":"2099-12-01"}
								""".formatted(org.adminId())))
				.andExpect(status().isCreated());
		OrgContext.runAs(org.orgId(), () -> transactionTemplate.execute(status -> {
			var incident = incidents.findById(incidentId).orElseThrow();
			var owner = members.find(org.adminId()).orElseThrow().getUser();
			var older = new Investigation(incident, "Older", "completed", 1, 10, 1, clock.instant().minusSeconds(1));
			older.addHypothesis(new InvestigationHypothesis(older, 1, "Outdated guess", 0.5, "Earlier evidence"));
			investigations.saveAndFlush(older);
			var latest = new Investigation(incident, "Latest", "completed", 1, 10, 1, clock.instant());
			latest.addHypothesis(new InvestigationHypothesis(latest, 1, "Gateway pool exhaustion", 0.9, "Latency spike"));
			var accepted = new InvestigationAction(latest, "Inspect gateway pool");
			accepted.decide(ActionDecision.ACCEPTED, owner, clock.instant());
			latest.addAction(accepted);
			var rejected = new InvestigationAction(latest, "Restart every service");
			rejected.decide(ActionDecision.REJECTED, owner, clock.instant());
			latest.addAction(rejected);
			latest.addAction(new InvestigationAction(latest, "Pending suggestion"));
			investigations.saveAndFlush(latest);
			return null;
		}));
		aiResponseBody.set("""
				{"aiGenerated":true,"kind":"SUMMARY","text":"Draft summary","postmortem":null}
				""");

		mvc.perform(post("/api/incidents/{id}/drafts", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"SUMMARY\"}"))
				.andExpect(status().isOk());

		assertThat(JsonPath.<List<Map<String, Object>>>read(lastAiRequest.get(), "$.hypotheses"))
				.containsExactly(Map.of("rank", 1, "title", "Gateway pool exhaustion", "confidence", 0.9));
		assertThat(JsonPath.<List<Map<String, Object>>>read(lastAiRequest.get(), "$.followUps"))
				.containsExactly(Map.of("title", "Add smoke check", "kind", "PREVENTIVE", "owner", "Admin",
						"dueDate", "2099-12-01", "status", "OPEN"));
		assertThat(JsonPath.<List<String>>read(lastAiRequest.get(), "$.acceptedActions"))
				.containsExactly("Inspect gateway pool");
		assertThat(lastAiRequest.get()).doesNotContain("acceptedHypotheses");
	}

	@Test
	void inventedIncidentAndEntryReferencesAreRemoved() throws Exception {
		aiResponseBody.set("""
				{
					"aiGenerated": true,
					"kind": "POSTMORTEM",
					"text": "%s mirrors INC-99999 and entry #88888, while entry #%d is real.",
					"postmortem": {
						"summary": "Real %s, incident #%d, fake INC-99999 and incident #99999.",
						"impact": "See entry #88888.",
						"rootCause": "Timeout.",
						"resolution": "Mitigated.",
						"wentWell": "Detected quickly.",
						"wentBadly": "Lacked alert."
					}
				}
				""".formatted(incidentRef, timelineEntryId, incidentRef, incidentId));

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
		assertThat(pmSummary).contains(incidentRef, "incident #" + incidentId).doesNotContain("99999");
		assertThat(pmImpact).doesNotContain("88888");
	}

	@Test
	void missingPostmortemUsesSanitizedOverviewAndKeepsAllSixFields() throws Exception {
		for (String textJson : List.of("null", "\"\"", "\"  \"", "\"Overview for " + incidentRef + ", fake INC-99999.\"")) {
			aiResponseBody.set("""
					{"aiGenerated":true,"kind":"POSTMORTEM","text":%s,"postmortem":null}
					""".formatted(textJson));
			String response = mvc.perform(post("/api/incidents/{id}/drafts", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"POSTMORTEM\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.generatedAt").isNotEmpty())
					.andReturn().getResponse().getContentAsString();
			String expected = textJson.contains("Overview") ? "Overview for " + incidentRef + ", fake." : "";
			assertThat(JsonPath.<String>read(response, "$.text")).isEqualTo(expected);
			assertThat(JsonPath.<Map<String, String>>read(response, "$.postmortem"))
					.isEqualTo(Map.of("summary", expected, "impact", "", "rootCause", "",
							"resolution", "", "wentWell", "", "wentBadly", ""));
		}
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
