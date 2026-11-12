package com.group2.incident.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.group2.incident.common.ratelimit.RateLimitService;
import com.group2.incident.incident.service.AiEmbeddingClient;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import com.github.benmanes.caffeine.cache.Cache;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Two orgs, A and B, each with one row of every kind (D48). Every endpoint, called by A's Admin with B's
 * ids, answers 404 or a field error, never B's data; every list holds only A's rows. Outside the test
 * transaction, so each request opens its session in its caller's org, as in production.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgIsolationTest {

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private RateLimitService rateLimitService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private AiEmbeddingClient aiEmbeddingClient;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	private SignedUp a;

	private SignedUp b;

	// B's ids by placeholder name, and A's service for the requests that name a person in a body.
	private final Map<String, Long> idsOfB = new HashMap<>();

	private long serviceOfA;

	private long sourceIncidentOfA;

	@BeforeEach
	void resetAiClient() {
		reset(aiEmbeddingClient);
	}

	@BeforeAll
	void createBothOrgs() throws Exception {
		// The sign-up limit is per address, and the previous test class may have used it up.
		((Cache<?, ?>) ReflectionTestUtils.getField(rateLimitService, "loginBuckets")).invalidateAll();
		a = orgs.signUp("isolation-a@a.test", "Isolation A");
		b = orgs.signUp("isolation-b@b.test", "Isolation B");
		serviceOfA = orgs.createService(a.adminToken(), "Payments", a.adminId());

		long serviceOfB = orgs.createService(b.adminToken(), "Payments", b.adminId());
		send(b, put("/api/services/{id}/on-call", serviceOfB), "{\"fallbackContactId\": %d, \"shifts\": []}".formatted(b.adminId()));
		send(b, put("/api/services/{id}/escalation-policy", serviceOfB),
				"{\"steps\": [{\"stepNumber\": 1, \"responderId\": %d, \"waitMinutes\": 15}]}".formatted(b.adminId()));
		String toolJson = send(b, post("/api/tools"), "{\"name\":\"Prometheus\"}");
		long toolOfB = TestOrgs.id(toolJson);
		String toolTokenOfB = JsonPath.read(toolJson, "$.token");
		String deployJson = mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolTokenOfB)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"service": "Payments", "version": "v1.0.0", "commit": "b0b0b0", "author": "CI", "deployedAt": "2026-11-09T10:00:00Z"}"""))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		long deployOfB = JsonPath.<Number>read(deployJson, "$.id").longValue();
		long monitorOfB = TestOrgs.id(send(b, post("/api/monitors"), """
				{"name": "Health", "url": "https://203.0.113.10/health", "expectedStatus": 200, "intervalSeconds": 60, "serviceId": %d, "failureThreshold": 3, "enabled": true}""".formatted(serviceOfB)));
		long heartbeatOfB = TestOrgs.id(send(b, post("/api/heartbeats"), """
				{"name": "Backup", "intervalSeconds": 60, "graceSeconds": 15, "serviceId": %d, "enabled": true}""".formatted(serviceOfB)));
		// The fallback contact becomes the assignee and gets a notification.
		String incidentJson = orgs.declareIncident(b.adminToken(), serviceOfB, "Checkout failing");
		long incidentOfB = TestOrgs.id(incidentJson);
		sourceIncidentOfA = TestOrgs.id(orgs.declareIncident(a.adminToken(), serviceOfA, "Checkout service latency"));
		long timelineEntryOfB = JsonPath.<Number>read(incidentJson, "$.timeline[0].id").longValue();
		String notifications = send(b, get("/api/notifications"), null);
		long notificationOfB = JsonPath.<Number>read(notifications, "$.notifications[0].id").longValue();

		idsOfB.put("serviceId", serviceOfB);
		idsOfB.put("userId", b.adminId());
		idsOfB.put("toolId", toolOfB);
		idsOfB.put("deployId", deployOfB);
		idsOfB.put("monitorId", monitorOfB);
		idsOfB.put("heartbeatId", heartbeatOfB);
		idsOfB.put("incidentId", incidentOfB);
		jdbcTemplate.update(
				"update incident set embedding = cast(? as incident.vector), embedding_retry_at = null where id = ? and org_id = ?",
				vector(0.8, 0.6), sourceIncidentOfA, a.orgId());
		jdbcTemplate.update(
				"update incident set embedding = cast(? as incident.vector), embedding_retry_at = null where id = ? and org_id = ?",
				vector(1, 0), incidentOfB, b.orgId());
		idsOfB.put("entryId", timelineEntryOfB);
		idsOfB.put("notificationId", notificationOfB);
		long tokenOfB = TestOrgs.id(send(b, post("/api/me/tokens"), "{\"name\":\"TokenOfB\"}"));
		idsOfB.put("tokenId", tokenOfB);
		idsOfB.put("runbookId", TestOrgs.id(send(b, post("/api/services/{id}/runbooks", serviceOfB),
				"{\"title\": \"Restart\", \"steps\": [\"Restart the service\"]}")));
		// Sign-up and the service each wrote an audit entry in B.
		idsOfB.put("auditId", JsonPath.<Number>read(send(b, get("/api/audit"), null), "$.content[0].id").longValue());
		long invOfB = jdbcTemplate.queryForObject(
				"insert into investigation (org_id, incident_id, summary, status, created_at) values (?, ?, 'Summary B', 'completed', now()) returning id",
				Long.class, b.orgId(), incidentOfB);
		long actionOfB = jdbcTemplate.queryForObject(
				"insert into investigation_action (investigation_id, text) values (?, 'Restart') returning id",
				Long.class, invOfB);
		idsOfB.put("investigationId", invOfB);
		idsOfB.put("actionId", actionOfB);
	}

	@AfterAll
	void deleteBothOrgs() {
		if (a != null) {
			orgs.delete(a.orgId());
		}
		if (b != null) {
			orgs.delete(b.orgId());
		}
	}

	Stream<Arguments> idEndpoints() {
		String target = "{\"responseMinutes\": 15, \"resolutionMinutes\": 240}";
		String serviceBody = """
				{"name": "Renamed", "description": "x", "ownerId": {userId}, "criticality": "LOW",
				 "targets": {"SEV1": %s, "SEV2": %s, "SEV3": %s, "SEV4": %s}}""".formatted(target, target, target, target);
		return Stream.of(
				Arguments.of("GET /api/services/{id}", HttpMethod.GET, "/api/services/{serviceId}", null),
				Arguments.of("PUT /api/services/{id}", HttpMethod.PUT, "/api/services/{serviceId}", serviceBody),
				Arguments.of("GET /api/services/{id}/on-call", HttpMethod.GET, "/api/services/{serviceId}/on-call", null),
				Arguments.of("PUT /api/services/{id}/on-call", HttpMethod.PUT, "/api/services/{serviceId}/on-call",
						"{\"fallbackContactId\": {userId}, \"shifts\": []}"),
				Arguments.of("GET /api/services/{id}/escalation-policy", HttpMethod.GET, "/api/services/{serviceId}/escalation-policy", null),
				Arguments.of("PUT /api/services/{id}/dependencies", HttpMethod.PUT, "/api/services/{serviceId}/dependencies",
						"{\"dependsOn\": []}"),
				Arguments.of("GET /api/services/{id}/blast-radius", HttpMethod.GET, "/api/services/{serviceId}/blast-radius", null),
				Arguments.of("GET /api/services/{id}/deploys", HttpMethod.GET, "/api/services/{serviceId}/deploys", null),
				Arguments.of("PUT /api/services/{id}/escalation-policy", HttpMethod.PUT, "/api/services/{serviceId}/escalation-policy",
						"{\"steps\": [{\"stepNumber\": 1, \"responderId\": {userId}, \"waitMinutes\": 15}]}"),
				Arguments.of("GET /api/incidents/{id}", HttpMethod.GET, "/api/incidents/{incidentId}", null),
				Arguments.of("GET /api/incidents/{id}/similar", HttpMethod.GET, "/api/incidents/{incidentId}/similar", null),
				Arguments.of("PUT /api/incidents/{id}/assignee", HttpMethod.PUT, "/api/incidents/{incidentId}/assignee",
						"{\"assigneeId\": {adminOfA}, \"expectedVersion\": 0}"),
				Arguments.of("PUT /api/incidents/{id}/roles", HttpMethod.PUT, "/api/incidents/{incidentId}/roles",
						"{\"role\":\"COMMS\",\"userId\": {adminOfA}, \"expectedVersion\": 0}"),
				Arguments.of("POST /api/incidents/{id}/comments", HttpMethod.POST, "/api/incidents/{incidentId}/comments",
						"{\"comment\":\"x\"}"),
				Arguments.of("POST /api/incidents/{id}/public-updates", HttpMethod.POST, "/api/incidents/{incidentId}/public-updates",
						"{\"status\":\"INVESTIGATING\",\"message\":\"x\"}"),
				Arguments.of("POST /api/incidents/{id}/evidence", HttpMethod.POST, "/api/incidents/{incidentId}/evidence",
						"{\"url\":\"https://example.com/logs\",\"label\":\"Logs\"}"),
				Arguments.of("POST /api/incidents/{id}/deploys/{deployId}", HttpMethod.POST,
						"/api/incidents/{incidentId}/deploys/{deployId}", null),
				Arguments.of("POST /api/incidents/{id}/timeline/{entryId}/corrections", HttpMethod.POST,
						"/api/incidents/{incidentId}/timeline/{entryId}/corrections", "{\"details\":\"x\"}"),
				Arguments.of("DELETE /api/tools/{id}", HttpMethod.DELETE, "/api/tools/{toolId}", null),
				Arguments.of("PUT /api/monitors/{id}", HttpMethod.PUT, "/api/monitors/{monitorId}", """
						{"name": "Health", "url": "https://203.0.113.10/health", "expectedStatus": 200, "intervalSeconds": 60, "serviceId": {serviceOfA}, "failureThreshold": 3, "enabled": true}"""),
				Arguments.of("GET /api/heartbeats/{id}", HttpMethod.GET, "/api/heartbeats/{heartbeatId}", null),
				Arguments.of("PUT /api/heartbeats/{id}", HttpMethod.PUT, "/api/heartbeats/{heartbeatId}", """
						{"name": "Backup", "intervalSeconds": 60, "graceSeconds": 15, "serviceId": {serviceOfA}, "enabled": true}"""),
				Arguments.of("POST /api/notifications/{id}/read", HttpMethod.POST, "/api/notifications/{notificationId}/read", null),
				Arguments.of("DELETE /api/me/tokens/{id}", HttpMethod.DELETE, "/api/me/tokens/{tokenId}", null),
				Arguments.of("GET /api/services/{id}/runbooks", HttpMethod.GET, "/api/services/{serviceId}/runbooks", null),
				Arguments.of("POST /api/services/{id}/runbooks", HttpMethod.POST, "/api/services/{serviceId}/runbooks",
						"{\"title\": \"Stolen\", \"steps\": [\"x\"]}"),
				Arguments.of("PUT /api/runbooks/{id}", HttpMethod.PUT, "/api/runbooks/{runbookId}", "{\"title\": \"Stolen\", \"steps\": [\"x\"]}"),
				Arguments.of("DELETE /api/runbooks/{id}", HttpMethod.DELETE, "/api/runbooks/{runbookId}", null),
				Arguments.of("PUT /api/users/{id}/role", HttpMethod.PUT, "/api/users/{userId}/role", "{\"role\":\"ADMIN\"}"),
				Arguments.of("PUT /api/users/{id}/enabled", HttpMethod.PUT, "/api/users/{userId}/enabled", "{\"enabled\":false}"),
				// Grafana holds every org's telemetry, so another org's service must be refused before any query.
				Arguments.of("GET /api/telemetry/logs with B's service", HttpMethod.GET, "/api/telemetry/logs?serviceId={serviceId}", null),
				Arguments.of("GET /api/telemetry/metrics with B's service", HttpMethod.GET,
						"/api/telemetry/metrics?serviceId={serviceId}&metric=errorRate", null),
				Arguments.of("POST /api/incidents/{id}/investigations", HttpMethod.POST,
						"/api/incidents/{incidentId}/investigations", null),
				Arguments.of("GET /api/incidents/{id}/investigations/latest", HttpMethod.GET,
						"/api/incidents/{incidentId}/investigations/latest", null),
				Arguments.of("POST /api/investigations/{id}/actions/{actionId}/decision", HttpMethod.POST,
						"/api/investigations/{investigationId}/actions/{actionId}/decision", "{\"decision\":\"ACCEPTED\"}"));
	}

	/** Every endpoint, called as A's Admin with B's ids, answers 404: never 403, never B's data. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("idEndpoints")
	void anotherOrgsIdsAreNotFound(String name, HttpMethod method, String pathTemplate, String body) throws Exception {
		var request = MockMvcRequestBuilders.request(method, expand(pathTemplate))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + a.adminToken());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(expand(body));
		}
		mvc.perform(request).andExpect(status().isNotFound());
	}

	Stream<Arguments> bodiesNamingAnotherOrgsRows() {
		String target = "{\"responseMinutes\": 15, \"resolutionMinutes\": 240}";
		return Stream.of(
				Arguments.of("POST /api/incidents with B's service", HttpMethod.POST, "/api/incidents",
						"{\"title\":\"x\",\"description\":\"x\",\"serviceId\":{serviceId},\"severity\":\"SEV2\"}"),
				Arguments.of("POST /api/services owned by B's Admin", HttpMethod.POST, "/api/services", """
						{"name": "Stolen owner", "description": "x", "ownerId": {userId}, "criticality": "LOW",
						 "targets": {"SEV1": %s, "SEV2": %s, "SEV3": %s, "SEV4": %s}}""".formatted(target, target, target, target)),
				Arguments.of("PUT on-call with B's Admin as fallback", HttpMethod.PUT, "/api/services/{serviceOfA}/on-call",
						"{\"fallbackContactId\": {userId}, \"shifts\": []}"),
				Arguments.of("PUT on-call with B's Admin on a shift", HttpMethod.PUT, "/api/services/{serviceOfA}/on-call",
						"{\"fallbackContactId\": {adminOfA}, \"shifts\": [{\"responderId\": {userId}, "
								+ "\"startTime\": \"2026-11-04T00:00:00Z\", \"endTime\": \"2026-11-05T00:00:00Z\"}]}"),
				Arguments.of("PUT escalation policy with B's Admin", HttpMethod.PUT, "/api/services/{serviceOfA}/escalation-policy",
						"{\"steps\": [{\"stepNumber\": 1, \"responderId\": {userId}, \"waitMinutes\": 15}]}"),
				Arguments.of("PUT dependencies on B's service", HttpMethod.PUT, "/api/services/{serviceOfA}/dependencies",
						"{\"dependsOn\": [{serviceId}]}"),
				Arguments.of("POST /api/monitors on B's service", HttpMethod.POST, "/api/monitors", """
						{"name": "Health", "url": "https://203.0.113.10/health", "expectedStatus": 200, "intervalSeconds": 60, "serviceId": {serviceId}, "failureThreshold": 3, "enabled": true}"""),
				Arguments.of("POST /api/heartbeats on B's service", HttpMethod.POST, "/api/heartbeats", """
						{"name": "Backup", "intervalSeconds": 60, "graceSeconds": 15, "serviceId": {serviceId}, "enabled": true}"""));
	}

	/**
	 * Users are global, so a request in A's own name that names B's service or B's people must be refused like
	 * an unknown id: 400 or 404, never saved.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("bodiesNamingAnotherOrgsRows")
	void anotherOrgsIdsInABodyAreRefused(String name, HttpMethod method, String pathTemplate, String body) throws Exception {
		mvc.perform(MockMvcRequestBuilders.request(method, expand(pathTemplate))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + a.adminToken())
						.contentType(MediaType.APPLICATION_JSON).content(expand(body)))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isIn(400, 404));
	}

	Stream<Arguments> listEndpoints() {
		return Stream.of(
				Arguments.of("GET /api/services", "/api/services", "$[*].id", "serviceId"),
				Arguments.of("GET /api/service-map", "/api/service-map", "$.nodes[*].id", "serviceId"),
				Arguments.of("GET /api/incidents", "/api/incidents", "$[*].id", "incidentId"),
				Arguments.of("GET /api/incidents/search", "/api/incidents/search?q=Checkout", "$.content[*].id", "incidentId"),
				Arguments.of("GET /api/alerts", "/api/alerts", "$[*].incidentId", "incidentId"),
				Arguments.of("GET /api/tools", "/api/tools", "$[*].id", "toolId"),
				Arguments.of("GET /api/monitors", "/api/monitors", "$[*].id", "monitorId"),
				Arguments.of("GET /api/heartbeats", "/api/heartbeats", "$[*].id", "heartbeatId"),
				Arguments.of("GET /api/users", "/api/users", "$[*].id", "userId"),
				Arguments.of("GET /api/notifications", "/api/notifications", "$.notifications[*].id", "notificationId"),
				Arguments.of("GET /api/audit", "/api/audit", "$.content[*].id", "auditId"));
	}

	/** Every list, read as A, holds none of B's rows. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("listEndpoints")
	void listsHoldOnlyTheCallersOrg(String name, String path, String idPath, String keyOfB) throws Exception {
		String body = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + a.adminToken()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<Number> ids = JsonPath.read(body, idPath);
		assertThat(ids).extracting(n -> n == null ? null : n.longValue()).doesNotContain(idsOfB.get(keyOfB));
	}

	@Test
	void similarSearchNeverReturnsAnEquallySimilarIncidentFromAnotherOrg() throws Exception {
		when(aiEmbeddingClient.isEnabled()).thenReturn(true);

		String response = mvc.perform(get("/api/incidents/{id}/similar", sourceIncidentOfA)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + a.adminToken()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(response).isEqualTo("[]");
	}

	@Test
	void duplicateCheckDoesNotReturnAMoreSimilarIncidentFromAnotherOrg() throws Exception {
		when(aiEmbeddingClient.isEnabled()).thenReturn(true);
		when(aiEmbeddingClient.embedQuery(anyString())).thenReturn(Optional.of(embedding(1, 0)));

		mvc.perform(get("/api/incidents/duplicate-check")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + a.adminToken())
						.queryParam("title", "Checkout service outage"))
				.andExpect(status().isOk())
						.andExpect(jsonPath("$.duplicate").value(true))
						.andExpect(jsonPath("$.incident.id").value(sourceIncidentOfA))
						.andExpect(jsonPath("$.incident.score").value(closeTo(0.8, 0.002)));
	}

	/** Keeps idEndpoints() complete, as SecurityMatrixTest does for roles. */
	@Test
	void everyEndpointWithAnIdIsCovered() {
		Set<String> covered = idEndpoints().map(arguments -> (String) arguments.get()[0]).collect(Collectors.toSet());
		Set<String> missing = new TreeSet<>();
		handlerMapping.getHandlerMethods().forEach((info, method) -> info.getPatternValues().stream()
				.filter(pattern -> pattern.contains("{"))
				// The public status page is open to every org by its slug; PublicStatusTest checks it shows one org only.
				.filter(pattern -> !pattern.startsWith("/api/public/"))
				.forEach(pattern -> info.getMethodsCondition().getMethods()
						.forEach(verb -> {
							String key = verb.name() + " " + pattern;
							if (!covered.contains(key)) {
								missing.add(key);
							}
						})));
		assertThat(missing).as("endpoints with an id that OrgIsolationTest does not call with another org's ids").isEmpty();
	}

	private String expand(String template) {
		String result = template.replace("{serviceOfA}", Long.toString(serviceOfA)).replace("{adminOfA}", Long.toString(a.adminId()));
		for (Map.Entry<String, Long> id : idsOfB.entrySet()) {
			result = result.replace("{" + id.getKey() + "}", id.getValue().toString());
		}
		return result;
	}

	private static String vector(double x, double y) {
		return "[" + x + "," + y + "," + "0,".repeat(765) + "0]";
	}

	private static List<Double> embedding(double x, double y) {
		List<Double> values = new ArrayList<>(java.util.Collections.nCopies(768, 0.0));
		values.set(0, x);
		values.set(1, y);
		return values;
	}

	private String send(SignedUp as, MockHttpServletRequestBuilder request, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, "Bearer " + as.adminToken());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
	}

}
