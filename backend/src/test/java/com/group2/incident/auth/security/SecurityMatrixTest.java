package com.group2.incident.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.alert.dto.CreateToolRequest;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.repository.MonitoringToolRepository;
import com.group2.incident.alert.service.ToolService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.incident.entity.EscalationPolicy;
import com.group2.incident.incident.entity.EscalationStep;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.repository.EscalationPolicyRepository;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.notification.entity.Notification;
import com.group2.incident.notification.repository.NotificationRepository;
import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@IntegrationTest
public class SecurityMatrixTest {

	private static final String DEFAULT_PASSWORD = "DevPassw0rd!";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Autowired
	private MonitoringToolRepository tools;

	@Autowired
	private ToolService toolService;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private NotificationRepository notifications;

	@Autowired
	private EscalationPolicyRepository escalationPolicies;

	private String adminToken;

	private String engineerToken;

	private String viewerToken;

	private Long targetUserId;

	private Long adminUserId;

	private Long targetServiceId;
	private Long targetToolId;
	private Long targetIncidentId;
	private Long adminNotificationId;
	private Long engineerNotificationId;
	private Long viewerNotificationId;

	public record MatrixRow(
			String name,
			HttpMethod method,
			String pathTemplate,
			String bodyTemplate,
			int adminStatus,
			int engineerStatus,
			int viewerStatus,
			int unauthenticatedStatus
	) {
		@Override
		public String toString() {
			return name;
		}
	}

	public static Stream<MatrixRow> matrixRows() {
		return Stream.of(
				new MatrixRow(
						"POST /api/auth/login",
						HttpMethod.POST,
						"/api/auth/login",
						"{\"username\":\"admin\",\"password\":\"" + DEFAULT_PASSWORD + "\"}",
						200, 200, 200, 200
				),
				new MatrixRow(
						"GET /api/auth/me",
						HttpMethod.GET,
						"/api/auth/me",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"POST /api/auth/logout",
						HttpMethod.POST,
						"/api/auth/logout",
						null,
						204, 204, 204, 401
				),
				new MatrixRow(
						"GET /api/users",
						HttpMethod.GET,
						"/api/users",
						null,
						200, 403, 403, 401
				),
				new MatrixRow(
						"POST /api/users",
						HttpMethod.POST,
						"/api/users",
						"{\"username\":\"sec_${RANDOM}\",\"displayName\":\"Sec User\",\"password\":\"ValidPass123!\",\"role\":\"ENGINEER\"}",
						201, 403, 403, 401
				),
				new MatrixRow(
						"PUT /api/users/{id}/role",
						HttpMethod.PUT,
						"/api/users/{id}/role",
						"{\"role\":\"ENGINEER\"}",
						200, 403, 403, 401
				),
				new MatrixRow(
						"GET /api/services",
						HttpMethod.GET,
						"/api/services",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"GET /api/services/{id}",
						HttpMethod.GET,
						"/api/services/{id}",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"POST /api/services",
						HttpMethod.POST,
						"/api/services",
						"{\"name\":\"SecSvc_${RANDOM}\",\"description\":\"Sec Svc\",\"ownerId\":${OWNER_ID},\"criticality\":\"HIGH\",\"targets\":{\"SEV1\":{\"responseMinutes\":15,\"resolutionMinutes\":240},\"SEV2\":{\"responseMinutes\":60,\"resolutionMinutes\":480},\"SEV3\":{\"responseMinutes\":240,\"resolutionMinutes\":2880},\"SEV4\":{\"responseMinutes\":1440,\"resolutionMinutes\":10080}}}",
						201, 403, 403, 401
				),
				new MatrixRow(
						"PUT /api/services/{id}",
						HttpMethod.PUT,
						"/api/services/{id}",
						"{\"name\":\"SecSvc_${RANDOM}\",\"description\":\"Sec Svc Updated\",\"ownerId\":${OWNER_ID},\"criticality\":\"HIGH\",\"targets\":{\"SEV1\":{\"responseMinutes\":15,\"resolutionMinutes\":240},\"SEV2\":{\"responseMinutes\":60,\"resolutionMinutes\":480},\"SEV3\":{\"responseMinutes\":240,\"resolutionMinutes\":2880},\"SEV4\":{\"responseMinutes\":1440,\"resolutionMinutes\":10080}}}",
						200, 403, 403, 401
				),
				new MatrixRow(
						"GET /api/services/{id}/on-call",
						HttpMethod.GET,
						"/api/services/{id}/on-call",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"PUT /api/services/{id}/on-call",
						HttpMethod.PUT,
						"/api/services/{id}/on-call",
						"{\"fallbackContactId\":${OWNER_ID},\"shifts\":[]}",
						200, 403, 403, 401
				),
				new MatrixRow(
						"GET /api/services/{id}/escalation-policy",
						HttpMethod.GET,
						"/api/services/{id}/escalation-policy",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"PUT /api/services/{id}/escalation-policy",
						HttpMethod.PUT,
						"/api/services/{id}/escalation-policy",
						"{\"steps\":[{\"stepNumber\":1,\"responderId\":${OWNER_ID},\"waitMinutes\":15}]}",
						200, 403, 403, 401
				),
				new MatrixRow(
						"GET /api/tools",
						HttpMethod.GET,
						"/api/tools",
						null,
						200, 403, 403, 401
				),
				new MatrixRow(
						"POST /api/tools",
						HttpMethod.POST,
						"/api/tools",
						"{\"name\":\"Tool_${RANDOM}\"}",
						201, 403, 403, 401
				),
				new MatrixRow(
						"DELETE /api/tools/{id}",
						HttpMethod.DELETE,
						"/api/tools/{id}",
						null,
						204, 403, 403, 401
				),
				new MatrixRow(
						"GET /api/incidents",
						HttpMethod.GET,
						"/api/incidents",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"GET /api/incidents/{id}",
						HttpMethod.GET,
						"/api/incidents/{id}",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"GET /api/alerts",
						HttpMethod.GET,
						"/api/alerts",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"POST /api/incidents",
						HttpMethod.POST,
						"/api/incidents",
						"{\"title\":\"Sec incident\",\"description\":\"Desc\",\"serviceId\":${SERVICE_ID},\"severity\":\"SEV2\"}",
						201, 201, 403, 401
				),
				new MatrixRow(
						"GET /api/notifications",
						HttpMethod.GET,
						"/api/notifications",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"POST /api/notifications/{id}/read",
						HttpMethod.POST,
						"/api/notifications/{id}/read",
						null,
						200, 200, 200, 401
				)
		);
	}

	@BeforeEach
	void setUp() throws Exception {
		AppUser admin = createUser("admin", Role.ADMIN);
		AppUser engineer = createUser("engineer", Role.ENGINEER);
		AppUser viewer = createUser("viewer", Role.VIEWER);
		AppUser target = createUser("target", Role.VIEWER);
		targetUserId = target.getId();
		adminUserId = admin.getId();

		var tool = tools.saveAndFlush(new MonitoringTool("Matrix Tool " + UUID.randomUUID(), "dummy_hash_" + UUID.randomUUID(), clock.instant()));
		targetToolId = tool.getId();

		adminToken = tokenFor("admin");
		engineerToken = tokenFor("engineer");
		viewerToken = tokenFor("viewer");

		ServiceEntity service = new ServiceEntity("SecurityMatrixBaseService", "Initial", admin, Criticality.HIGH, clock.instant());
		service.putTarget(Severity.SEV1, 15, 240);
		service.putTarget(Severity.SEV2, 60, 480);
		service.putTarget(Severity.SEV3, 240, 2880);
		service.putTarget(Severity.SEV4, 1440, 10080);
		ServiceEntity savedService = services.saveAndFlush(service);
		targetServiceId = savedService.getId();

		EscalationPolicy policy = new EscalationPolicy(savedService, clock.instant());
		policy.replaceSteps(List.of(new EscalationStep(1, admin, 15)), clock.instant());
		escalationPolicies.saveAndFlush(policy);

		Incident incident = incidents.saveAndFlush(new Incident("SecurityMatrixBaseIncident", "Initial", savedService,
				Severity.SEV2, IncidentStatus.OPEN, admin, clock.instant(), 60, 480));
		targetIncidentId = incident.getId();

		adminNotificationId = notifications.saveAndFlush(
				new Notification(admin, incident, "Admin notification", clock.instant())).getId();
		engineerNotificationId = notifications.saveAndFlush(
				new Notification(engineer, incident, "Engineer notification", clock.instant())).getId();
		viewerNotificationId = notifications.saveAndFlush(
				new Notification(viewer, incident, "Viewer notification", clock.instant())).getId();
	}

	@Test
	void failsIfNewEndpointIsAddedWithoutItsRule() {
		Set<String> declaredEndpoints = new HashSet<>();
		matrixRows().forEach(r -> declaredEndpoints.add(r.method().name() + " " + r.pathTemplate()));

		Set<String> registeredApiEndpoints = new HashSet<>();
		for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
			Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
			Set<String> patterns = info.getPathPatternsCondition() != null
					? info.getPathPatternsCondition().getPatternValues()
					: info.getDirectPaths();

			for (String pattern : patterns) {
				if (pattern.startsWith("/api/")) {
					for (RequestMethod method : methods) {
						registeredApiEndpoints.add(method.name() + " " + pattern);
					}
				}
			}
		}

		assertThat(declaredEndpoints)
				.as("Every registered /api endpoint must be covered in SecurityMatrixTest. Missing rules: %s",
						registeredApiEndpoints.stream().filter(e -> !declaredEndpoints.contains(e)).toList())
				.containsAll(registeredApiEndpoints);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("matrixRows")
	void endpointSecurityMatrix(MatrixRow row) throws Exception {
		// Admin
		execute(row.method(), resolvePath(row.pathTemplate(), adminNotificationId), row.bodyTemplate(), adminToken, row.adminStatus());

		// Engineer
		execute(row.method(), resolvePath(row.pathTemplate(), engineerNotificationId), row.bodyTemplate(), engineerToken, row.engineerStatus());

		// Viewer
		execute(row.method(), resolvePath(row.pathTemplate(), viewerNotificationId), row.bodyTemplate(), viewerToken, row.viewerStatus());

		// Unauthenticated
		execute(row.method(), resolvePath(row.pathTemplate(), adminNotificationId), row.bodyTemplate(), null, row.unauthenticatedStatus());
	}

	private String resolvePath(String pathTemplate, Long notificationId) {
		String id = pathTemplate.startsWith("/api/tools/") ? targetToolId.toString()
				: pathTemplate.startsWith("/api/services/") ? targetServiceId.toString()
				: pathTemplate.startsWith("/api/incidents/") ? targetIncidentId.toString()
				: pathTemplate.startsWith("/api/notifications/") ? notificationId.toString()
				: targetUserId.toString();
		return pathTemplate.replace("{id}", id);
	}

	private void execute(HttpMethod method, String path, String bodyTemplate, String token, int expectedStatus) throws Exception {
		MockHttpServletRequestBuilder req = request(method, path);
		if (token != null) {
			req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		if (bodyTemplate != null) {
			String body = bodyTemplate.replace("${RANDOM}", UUID.randomUUID().toString().substring(0, 8))
					.replace("${OWNER_ID}", adminUserId.toString())
					.replace("${SERVICE_ID}", targetServiceId.toString());
			req.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		mvc.perform(req).andExpect(status().is(expectedStatus));
	}

	private AppUser createUser(String username, Role role) {
		return users.saveAndFlush(new AppUser(username, "Test " + username, passwordEncoder.encode(DEFAULT_PASSWORD), role,
				clock.instant()));
	}

	private String tokenFor(String username) throws Exception {
		String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, DEFAULT_PASSWORD);
		String response = mvc.perform(
						request(HttpMethod.POST, "/api/auth/login")
								.contentType(MediaType.APPLICATION_JSON)
								.content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.token");
	}

	@Test
	void webhookAlertsAuthenticationMatrix() throws Exception {
		String validPayload = """
				{
					"source": "prometheus",
					"eventId": "evt-matrix-1",
					"service": "SecurityMatrixBaseService",
					"title": "Alert",
					"description": "Desc",
					"severity": "SEV1"
				}
				""";

		// 1. Missing token -> 401
		mvc.perform(request(HttpMethod.POST, "/webhooks/alerts")
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());

		// 2. Wrong tool token -> 401
		mvc.perform(request(HttpMethod.POST, "/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer tt_invalid_token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());

		// 3. User JWT tokens (Admin, Engineer, Viewer) are not valid tool tokens -> 401
		mvc.perform(request(HttpMethod.POST, "/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());

		mvc.perform(request(HttpMethod.POST, "/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());

		mvc.perform(request(HttpMethod.POST, "/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());

		// 4. Valid registered tool token -> 202 Accepted
		var toolCreateResponse = toolService.createTool(
				new CreateToolRequest("Matrix Alert Tool " + UUID.randomUUID()),
				adminUserId
		);
		mvc.perform(request(HttpMethod.POST, "/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolCreateResponse.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isAccepted());

		// 5. Revoked tool token -> 401
		toolService.revokeTool(toolCreateResponse.id(), adminUserId);
		mvc.perform(request(HttpMethod.POST, "/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolCreateResponse.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());
	}
}
