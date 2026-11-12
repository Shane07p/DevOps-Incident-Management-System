package com.group2.incident.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.alert.dto.CreateToolRequest;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.repository.MonitoringToolRepository;
import com.group2.incident.alert.service.ToolService;
import com.group2.incident.auth.dto.CreatePersonalAccessTokenRequest;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.auth.service.PersonalAccessTokenService;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.Runbook;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.RunbookRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.deploy.entity.Deployment;
import com.group2.incident.deploy.repository.DeploymentRepository;
import com.group2.incident.heartbeat.entity.Heartbeat;
import com.group2.incident.heartbeat.repository.HeartbeatRepository;
import com.group2.incident.incident.entity.EscalationPolicy;
import com.group2.incident.incident.entity.EscalationStep;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.monitor.entity.UptimeMonitor;
import com.group2.incident.monitor.repository.UptimeMonitorRepository;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.repository.EscalationPolicyRepository;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.notification.entity.Notification;
import com.group2.incident.notification.repository.NotificationRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.group2.incident.support.TestOrgExecutionListener;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
	private UptimeMonitorRepository monitors;

	@Autowired
	private HeartbeatRepository heartbeats;

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private RunbookRepository runbooks;

	@Autowired
	private JdbcTemplate jdbc;

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

	@Autowired
	private PersonalAccessTokenService patService;

	@Autowired
	private DeploymentRepository deployments;

	private String adminToken;

	private String engineerToken;

	private String viewerToken;

	private Long targetUserId;

	private Long adminUserId;

	private Long engineerUserId;

	private Long targetServiceId;
	private Long targetToolId;
	private Long targetIncidentId;
	private Long targetDeployId;
	private Long targetMonitorId;
	private Long targetHeartbeatId;
	private String targetHeartbeatToken;
	private Long adminNotificationId;
	private Long engineerNotificationId;
	private Long viewerNotificationId;
	private Long adminPatId;
	private Long engineerPatId;
	private Long viewerPatId;
	// One runbook each: the Admin deletes theirs before the Engineer tries.
	private Long adminRunbookId;
	private Long engineerRunbookId;
	private Long viewerRunbookId;

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
						"{\"email\":\"admin@test.local\",\"password\":\"" + DEFAULT_PASSWORD + "\"}",
						200, 200, 200, 200
				),
				// The matrix sends no body, so every caller gets 400: the endpoint is reachable without login.
				new MatrixRow(
						"POST /api/auth/signup",
						HttpMethod.POST,
						"/api/auth/signup",
						null,
						400, 400, 400, 400
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
						"{\"email\":\"sec_${RANDOM}@test.local\",\"displayName\":\"Sec User\",\"password\":\"ValidPass123!\",\"role\":\"ENGINEER\"}",
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
						"PUT /api/services/{id}/dependencies",
						HttpMethod.PUT,
						"/api/services/{id}/dependencies",
						"{\"dependsOn\":[]}",
						200, 403, 403, 401
				),
				new MatrixRow(
						"GET /api/services/{id}/blast-radius",
						HttpMethod.GET,
						"/api/services/{id}/blast-radius",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"GET /api/services/{id}/deploys",
						HttpMethod.GET,
						"/api/services/{id}/deploys",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"GET /api/service-map",
						HttpMethod.GET,
						"/api/service-map",
						null,
						200, 200, 200, 401
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
						"GET /api/monitors",
						HttpMethod.GET,
						"/api/monitors",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"POST /api/monitors",
						HttpMethod.POST,
						"/api/monitors",
						"{\"name\":\"Mon_${RANDOM}\",\"url\":\"https://203.0.113.10/health\",\"expectedStatus\":200,\"intervalSeconds\":60,\"serviceId\":${SERVICE_ID},\"failureThreshold\":3,\"enabled\":true}",
						201, 403, 403, 401
				),
				new MatrixRow(
						"PUT /api/monitors/{id}",
						HttpMethod.PUT,
						"/api/monitors/{id}",
						"{\"name\":\"Mon_${RANDOM}\",\"url\":\"https://203.0.113.10/health\",\"expectedStatus\":200,\"intervalSeconds\":60,\"serviceId\":${SERVICE_ID},\"failureThreshold\":3,\"enabled\":true}",
						200, 403, 403, 401
				),
				new MatrixRow(
						"GET /api/heartbeats",
						HttpMethod.GET,
						"/api/heartbeats",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"GET /api/heartbeats/{id}",
						HttpMethod.GET,
						"/api/heartbeats/{id}",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"POST /api/heartbeats",
						HttpMethod.POST,
						"/api/heartbeats",
						"{\"name\":\"HB_${RANDOM}\",\"serviceId\":${SERVICE_ID},\"intervalSeconds\":60,\"graceSeconds\":10,\"enabled\":true}",
						201, 403, 403, 401
				),
				new MatrixRow(
						"PUT /api/heartbeats/{id}",
						HttpMethod.PUT,
						"/api/heartbeats/{id}",
						"{\"name\":\"HB_${RANDOM}\",\"serviceId\":${SERVICE_ID},\"intervalSeconds\":60,\"graceSeconds\":10,\"enabled\":true}",
						200, 403, 403, 401
				),
				new MatrixRow(
						"POST /api/public/heartbeats/ping/{token}",
						HttpMethod.POST,
						"/api/public/heartbeats/ping/{token}",
						null,
						200, 200, 200, 200
				),
				new MatrixRow(
						"GET /api/public/heartbeats/ping/{token}",
						HttpMethod.GET,
						"/api/public/heartbeats/ping/{token}",
						null,
						200, 200, 200, 200
				),
				new MatrixRow(
						"GET /api/incidents",
						HttpMethod.GET,
						"/api/incidents",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"GET /api/incidents/search",
						HttpMethod.GET,
						"/api/incidents/search",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"GET /api/incidents/{id}/similar",
						HttpMethod.GET,
						"/api/incidents/{id}/similar",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"GET /api/incidents/duplicate-check",
						HttpMethod.GET,
						"/api/incidents/duplicate-check",
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
						"PUT /api/incidents/{id}/assignee",
						HttpMethod.PUT,
						"/api/incidents/{id}/assignee",
						"{\"assigneeId\":${ASSIGNEE_ID},\"expectedVersion\":${INCIDENT_VERSION}}",
						200, 200, 403, 401
				),
				new MatrixRow(
						"PUT /api/incidents/{id}/roles",
						HttpMethod.PUT,
						"/api/incidents/{id}/roles",
						"{\"role\":\"COMMS\",\"userId\":${ASSIGNEE_ID},\"expectedVersion\":${INCIDENT_VERSION}}",
						200, 200, 403, 401
				),
				new MatrixRow(
						"POST /api/incidents/{id}/comments",
						HttpMethod.POST,
						"/api/incidents/{id}/comments",
						"{\"comment\":\"Security matrix comment\"}",
						201, 201, 403, 401
				),
				new MatrixRow(
						"POST /api/incidents/{id}/evidence",
						HttpMethod.POST,
						"/api/incidents/{id}/evidence",
						"{\"url\":\"https://grafana.example.com/logs\",\"label\":\"Grafana logs\"}",
						201, 201, 403, 401
				),
				new MatrixRow(
						"POST /api/incidents/{id}/deploys/{deployId}",
						HttpMethod.POST,
						"/api/incidents/{id}/deploys/{deployId}",
						null,
						200, 200, 403, 401
				),
				new MatrixRow(
						"POST /api/incidents/{id}/timeline/{entryId}/corrections",
						HttpMethod.POST,
						"/api/incidents/{id}/timeline/{entryId}/corrections",
						"{\"details\":\"Security matrix correction\"}",
						404, 404, 403, 401
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
				),
				new MatrixRow(
						"GET /api/services/{id}/runbooks",
						HttpMethod.GET,
						"/api/services/{id}/runbooks",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"POST /api/services/{id}/runbooks",
						HttpMethod.POST,
						"/api/services/{id}/runbooks",
						"{\"title\":\"Matrix ${RANDOM}\",\"steps\":[\"Restart\"]}",
						201, 201, 403, 401
				),
				new MatrixRow(
						"PUT /api/runbooks/{id}",
						HttpMethod.PUT,
						"/api/runbooks/{id}",
						"{\"title\":\"Matrix ${RANDOM}\",\"steps\":[\"Restart\"]}",
						200, 200, 403, 401
				),
				new MatrixRow(
						"DELETE /api/runbooks/{id}",
						HttpMethod.DELETE,
						"/api/runbooks/{id}",
						null,
						204, 204, 403, 401
				),
				// Enabling a member who is already enabled changes nothing, so the row repeats safely.
				new MatrixRow(
						"PUT /api/users/{id}/enabled",
						HttpMethod.PUT,
						"/api/users/{id}/enabled",
						"{\"enabled\":true}",
						200, 403, 403, 401
				),
				new MatrixRow(
						"GET /api/audit",
						HttpMethod.GET,
						"/api/audit",
						null,
						200, 403, 403, 401
				),
				new MatrixRow(
						"GET /api/me/tokens",
						HttpMethod.GET,
						"/api/me/tokens",
						null,
						200, 200, 200, 401
				),
				new MatrixRow(
						"POST /api/me/tokens",
						HttpMethod.POST,
						"/api/me/tokens",
						"{\"name\":\"MatrixToken-${RANDOM}\"}",
						201, 201, 201, 401
				),
				new MatrixRow(
						"DELETE /api/me/tokens/{id}",
						HttpMethod.DELETE,
						"/api/me/tokens/{id}",
						null,
						204, 204, 204, 401
				),
				// Customers have no account, so everyone reads the page, logged in or not (D45).
				new MatrixRow(
						"GET /api/public/status/{slug}",
						HttpMethod.GET,
						"/api/public/status/{slug}",
						null,
						200, 200, 200, 200
				),
				// The matrix Engineer is neither the incident's Lead nor its Comms (D45, TC-FR-21.2a).
				new MatrixRow(
						"POST /api/incidents/{id}/public-updates",
						HttpMethod.POST,
						"/api/incidents/{id}/public-updates",
						"{\"status\":\"INVESTIGATING\",\"message\":\"Matrix update\"}",
						201, 403, 403, 401
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
		engineerUserId = engineer.getId();

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
		service.publishAs(true, "Matrix service", clock.instant());
		ServiceEntity savedService = services.saveAndFlush(service);
		targetServiceId = savedService.getId();

		EscalationPolicy policy = new EscalationPolicy(savedService, clock.instant());
		policy.replaceSteps(List.of(new EscalationStep(1, admin, 15)), clock.instant());
		escalationPolicies.saveAndFlush(policy);

		Incident unsaved = new Incident("SecurityMatrixBaseIncident", "Initial", savedService,
				Severity.SEV2, IncidentStatus.OPEN, admin, clock.instant(), 60, 480);
		// Far above the numbers the incident API hands out in the test org.
		unsaved.assignNumber(990_001L);
		Incident incident = incidents.saveAndFlush(unsaved);
		targetIncidentId = incident.getId();

		// A documentation-only address (RFC 5737): nothing is ever contacted, since the worker is off in tests.
		targetMonitorId = monitors.saveAndFlush(new UptimeMonitor(savedService, "Matrix monitor", "https://203.0.113.10/health",
				200, 60, 3, true, clock.instant())).getId();

		adminNotificationId = notifications.saveAndFlush(
				new Notification(admin, incident, "Admin notification", clock.instant())).getId();
		engineerNotificationId = notifications.saveAndFlush(
				new Notification(engineer, incident, "Engineer notification", clock.instant())).getId();
		viewerNotificationId = notifications.saveAndFlush(
				new Notification(viewer, incident, "Viewer notification", clock.instant())).getId();

		targetDeployId = deployments.saveAndFlush(new Deployment(
				savedService,
				"v1.0.0-sec",
				"abcdef1234567890",
				"Security Author",
				clock.instant()
		)).getId();

		adminPatId = patService.createToken(new CreatePersonalAccessTokenRequest("admin-matrix-token"), adminUserId).id();
		engineerPatId = patService.createToken(new CreatePersonalAccessTokenRequest("engineer-matrix-token"), engineerUserId).id();
		viewerPatId = patService.createToken(new CreatePersonalAccessTokenRequest("viewer-matrix-token"), viewer.getId()).id();
		adminRunbookId = runbooks.saveAndFlush(new Runbook(savedService, "Admin runbook", List.of("Step"), clock.instant())).getId();
		engineerRunbookId = runbooks.saveAndFlush(new Runbook(savedService, "Engineer runbook", List.of("Step"), clock.instant())).getId();
		viewerRunbookId = runbooks.saveAndFlush(new Runbook(savedService, "Viewer runbook", List.of("Step"), clock.instant())).getId();

		Heartbeat targetHeartbeat = heartbeats.saveAndFlush(new Heartbeat(
				savedService,
				"Matrix heartbeat",
				60,
				10,
				UUID.randomUUID().toString().replace("-", ""),
				true,
				clock.instant()
		));
		targetHeartbeatId = targetHeartbeat.getId();
		targetHeartbeatToken = targetHeartbeat.getToken();
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
		execute(row.method(), resolvePath(row.pathTemplate(), adminNotificationId, adminPatId, adminRunbookId), row.bodyTemplate(), adminToken, row.adminStatus());

		// Engineer
		execute(row.method(), resolvePath(row.pathTemplate(), engineerNotificationId, engineerPatId, engineerRunbookId), row.bodyTemplate(), engineerToken, row.engineerStatus());

		// Viewer
		execute(row.method(), resolvePath(row.pathTemplate(), viewerNotificationId, viewerPatId, viewerRunbookId), row.bodyTemplate(), viewerToken, row.viewerStatus());

		// Unauthenticated
		execute(row.method(), resolvePath(row.pathTemplate(), adminNotificationId, adminPatId, adminRunbookId), row.bodyTemplate(), null, row.unauthenticatedStatus());
	}

	private String resolvePath(String pathTemplate, Long notificationId, Long patId, Long runbookId) {
		String id = pathTemplate.startsWith("/api/tools/") ? targetToolId.toString()
				: pathTemplate.startsWith("/api/services/") ? targetServiceId.toString()
				: pathTemplate.startsWith("/api/incidents/") ? targetIncidentId.toString()
				: pathTemplate.startsWith("/api/notifications/") ? notificationId.toString()
				: pathTemplate.startsWith("/api/monitors/") ? targetMonitorId.toString()
				: pathTemplate.startsWith("/api/heartbeats/") ? targetHeartbeatId.toString()
				: pathTemplate.startsWith("/api/me/tokens/") ? (patId != null ? patId.toString() : "999999")
				: pathTemplate.startsWith("/api/runbooks/") ? runbookId.toString()
				: targetUserId.toString();
		return pathTemplate.replace("{id}", id)
				.replace("{slug}", TestOrgExecutionListener.TEST_ORG_SLUG)
				.replace("{entryId}", "999999999")
				.replace("{token}", targetHeartbeatToken != null ? targetHeartbeatToken : "dummy_token")
				.replace("{deployId}", targetDeployId != null ? targetDeployId.toString() : "999999999");
	}

	private void execute(HttpMethod method, String path, String bodyTemplate, String token, int expectedStatus) throws Exception {
		MockHttpServletRequestBuilder req = request(method, path);
		if (path.equals("/api/incidents/search")) {
			req.queryParam("q", "SecurityMatrix");
		}
		if (path.equals("/api/incidents/duplicate-check")) {
			req.queryParam("title", "SecurityMatrix incident");
		}
		if (token != null) {
			req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		if (bodyTemplate != null) {
			Long assigneeId = (token != null && token.equals(adminToken)) ? engineerUserId : adminUserId;
			String body = bodyTemplate.replace("${RANDOM}", UUID.randomUUID().toString().substring(0, 8))
					.replace("${OWNER_ID}", adminUserId.toString())
					.replace("${ASSIGNEE_ID}", assigneeId != null ? assigneeId.toString() : "")
					.replace("${SERVICE_ID}", targetServiceId.toString())
					// The version the incident has now, as a screen that just loaded it would send.
					.replace("${INCIDENT_VERSION}", String.valueOf(jdbc.queryForObject(
							"SELECT version FROM incident WHERE id = ?", Long.class, targetIncidentId)));
			req.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		mvc.perform(req).andExpect(status().is(expectedStatus));
	}

	private AppUser createUser(String username, Role role) {
		return accounts.save(new AppUser(username + "@test.local", "Test " + username, passwordEncoder.encode(DEFAULT_PASSWORD), clock.instant()), role);
	}

	private String tokenFor(String username) throws Exception {
		String body = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(username + "@test.local", DEFAULT_PASSWORD);
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

	@Test
	void webhookDeploysAuthenticationMatrix() throws Exception {
		String validPayload = """
				{
					"service": "SecurityMatrixBaseService",
					"version": "v1.0.0",
					"commit": "abcdef1234567890",
					"author": "Alice Developer",
					"deployedAt": "2026-11-09T10:00:00Z"
				}
				""";

		// 1. Missing token -> 401
		mvc.perform(request(HttpMethod.POST, "/webhooks/deploys")
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());

		// 2. Wrong tool token -> 401
		mvc.perform(request(HttpMethod.POST, "/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer tt_invalid_token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());

		// 3. User JWT tokens (Admin, Engineer, Viewer) are not valid tool tokens -> 401
		mvc.perform(request(HttpMethod.POST, "/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());

		mvc.perform(request(HttpMethod.POST, "/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());

		mvc.perform(request(HttpMethod.POST, "/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());

		// 4. Valid registered tool token -> 202 Accepted
		var toolCreateResponse = toolService.createTool(
				new CreateToolRequest("Matrix Deploy Tool " + UUID.randomUUID()),
				adminUserId
		);
		mvc.perform(request(HttpMethod.POST, "/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolCreateResponse.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isAccepted());

		// 5. Revoked tool token -> 401
		toolService.revokeTool(toolCreateResponse.id(), adminUserId);
		mvc.perform(request(HttpMethod.POST, "/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolCreateResponse.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content(validPayload))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void personalAccessTokenInheritsOwnerRole() throws Exception {
		var viewerPat = patService.createToken(new CreatePersonalAccessTokenRequest("viewer-pat-action"), targetUserId);
		var engineerPat = patService.createToken(new CreatePersonalAccessTokenRequest("eng-pat-action"), engineerUserId);

		// Viewer's PAT can read incidents (200)
		mvc.perform(request(HttpMethod.GET, "/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerPat.token()))
				.andExpect(status().isOk());

		// Viewer's PAT is refused on Engineer-only incident creation (403) - TC-FR-23.2b
		mvc.perform(request(HttpMethod.POST, "/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerPat.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"title": "Unauthorized incident",
									"description": "Viewer cannot create",
									"serviceId": %d,
									"severity": "SEV3"
								}
								""".formatted(targetServiceId)))
				.andExpect(status().isForbidden());

		// Engineer's PAT can create incident (201)
		mvc.perform(request(HttpMethod.POST, "/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerPat.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"title": "Authorized incident",
									"description": "Engineer can create",
									"serviceId": %d,
									"severity": "SEV3"
								}
								""".formatted(targetServiceId)))
				.andExpect(status().isCreated());
	}
}
