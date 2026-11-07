package com.group2.incident.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.common.ratelimit.RateLimitService;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import com.github.benmanes.caffeine.cache.Cache;
import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	private SignedUp a;

	private SignedUp b;

	// B's ids by placeholder name, and A's service for the requests that name a person in a body.
	private final Map<String, Long> idsOfB = new HashMap<>();

	private long serviceOfA;

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
		long toolOfB = TestOrgs.id(send(b, post("/api/tools"), "{\"name\":\"Prometheus\"}"));
		long monitorOfB = TestOrgs.id(send(b, post("/api/monitors"), """
				{"name": "Health", "url": "https://203.0.113.10/health", "expectedStatus": 200, "intervalSeconds": 60, "serviceId": %d, "failureThreshold": 3, "enabled": true}""".formatted(serviceOfB)));
		// The fallback contact becomes the assignee and gets a notification.
		String incidentJson = orgs.declareIncident(b.adminToken(), serviceOfB, "Checkout failing");
		long incidentOfB = TestOrgs.id(incidentJson);
		long timelineEntryOfB = JsonPath.<Number>read(incidentJson, "$.timeline[0].id").longValue();
		String notifications = send(b, get("/api/notifications"), null);
		long notificationOfB = JsonPath.<Number>read(notifications, "$.notifications[0].id").longValue();

		idsOfB.put("serviceId", serviceOfB);
		idsOfB.put("userId", b.adminId());
		idsOfB.put("toolId", toolOfB);
		idsOfB.put("monitorId", monitorOfB);
		idsOfB.put("incidentId", incidentOfB);
		idsOfB.put("entryId", timelineEntryOfB);
		idsOfB.put("notificationId", notificationOfB);
		// Sign-up and the service each wrote an audit entry in B.
		idsOfB.put("auditId", JsonPath.<Number>read(send(b, get("/api/audit"), null), "$.content[0].id").longValue());
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
				Arguments.of("PUT /api/services/{id}/escalation-policy", HttpMethod.PUT, "/api/services/{serviceId}/escalation-policy",
						"{\"steps\": [{\"stepNumber\": 1, \"responderId\": {userId}, \"waitMinutes\": 15}]}"),
				Arguments.of("GET /api/incidents/{id}", HttpMethod.GET, "/api/incidents/{incidentId}", null),
				Arguments.of("PUT /api/incidents/{id}/assignee", HttpMethod.PUT, "/api/incidents/{incidentId}/assignee",
						"{\"assigneeId\": {adminOfA}, \"expectedVersion\": 0}"),
				Arguments.of("PUT /api/incidents/{id}/roles", HttpMethod.PUT, "/api/incidents/{incidentId}/roles",
						"{\"role\":\"COMMS\",\"userId\": {adminOfA}, \"expectedVersion\": 0}"),
				Arguments.of("POST /api/incidents/{id}/comments", HttpMethod.POST, "/api/incidents/{incidentId}/comments",
						"{\"comment\":\"x\"}"),
				Arguments.of("POST /api/incidents/{id}/evidence", HttpMethod.POST, "/api/incidents/{incidentId}/evidence",
						"{\"url\":\"https://example.com/logs\",\"label\":\"Logs\"}"),
				Arguments.of("POST /api/incidents/{id}/timeline/{entryId}/corrections", HttpMethod.POST,
						"/api/incidents/{incidentId}/timeline/{entryId}/corrections", "{\"details\":\"x\"}"),
				Arguments.of("DELETE /api/tools/{id}", HttpMethod.DELETE, "/api/tools/{toolId}", null),
				Arguments.of("PUT /api/monitors/{id}", HttpMethod.PUT, "/api/monitors/{monitorId}", """
						{"name": "Health", "url": "https://203.0.113.10/health", "expectedStatus": 200, "intervalSeconds": 60, "serviceId": {serviceOfA}, "failureThreshold": 3, "enabled": true}"""),
				Arguments.of("POST /api/notifications/{id}/read", HttpMethod.POST, "/api/notifications/{notificationId}/read", null),
				Arguments.of("PUT /api/users/{id}/role", HttpMethod.PUT, "/api/users/{userId}/role", "{\"role\":\"ADMIN\"}"));
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
						{"name": "Health", "url": "https://203.0.113.10/health", "expectedStatus": 200, "intervalSeconds": 60, "serviceId": {serviceId}, "failureThreshold": 3, "enabled": true}"""));
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

	/** Keeps idEndpoints() complete, as SecurityMatrixTest does for roles. */
	@Test
	void everyEndpointWithAnIdIsCovered() {
		Set<String> covered = idEndpoints().map(arguments -> (String) arguments.get()[0]).collect(Collectors.toSet());
		Set<String> missing = new TreeSet<>();
		handlerMapping.getHandlerMethods().forEach((info, method) -> info.getPatternValues().stream()
				.filter(pattern -> pattern.contains("{"))
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

	private String send(SignedUp as, MockHttpServletRequestBuilder request, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, "Bearer " + as.adminToken());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
	}

}
