package com.group2.incident.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.group2.incident.alert.dto.CreateToolRequest;
import com.group2.incident.alert.dto.CreateToolResponse;
import com.group2.incident.alert.repository.MonitoringToolRepository;
import com.group2.incident.alert.service.ToolService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.deploy.entity.Deployment;
import com.group2.incident.deploy.repository.DeploymentRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class DeployWebhookTest {

	private static final String VALID_PAYLOAD = """
			{
				"service": "Payments",
				"version": "v1.2.3",
				"commit": "abcdef0123456789",
				"author": "Alice Developer",
				"deployedAt": "2026-11-09T10:00:00Z"
			}
			""";

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ToolService toolService;

	@Autowired
	private MonitoringToolRepository tools;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private DeploymentRepository deployments;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private TimelineEntryRepository timelineEntries;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	private AppUser admin;
	private AppUser engineer;
	private AppUser viewer;
	private String adminJwt;
	private String engineerJwt;
	private String viewerJwt;
	private String validToolToken;
	private Long toolId;
	private ServiceEntity payments;

	@BeforeEach
	void setUp() throws Exception {
		admin = accounts.save(new AppUser("deploy_admin@test.local", "Deploy Admin", passwordEncoder.encode("DevPassw0rd!"), clock.instant()), Role.ADMIN);
		engineer = accounts.save(new AppUser("deploy_eng@test.local", "Deploy Engineer", passwordEncoder.encode("DevPassw0rd!"), clock.instant()), Role.ENGINEER);
		viewer = accounts.save(new AppUser("deploy_view@test.local", "Deploy Viewer", passwordEncoder.encode("DevPassw0rd!"), clock.instant()), Role.VIEWER);
		payments = createService("Payments");

		CreateToolResponse created = toolService.createTool(new CreateToolRequest("Deploy Tool " + clock.millis()), admin.getId());
		validToolToken = created.token();
		toolId = created.id();

		adminJwt = loginAndGetToken("deploy_admin@test.local");
		engineerJwt = loginAndGetToken("deploy_eng@test.local");
		viewerJwt = loginAndGetToken("deploy_view@test.local");
	}

	private String loginAndGetToken(String email) throws Exception {
		String loginResponse = mvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"DevPassw0rd!\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(loginResponse, "$.token");
	}

	@Test
	void validTokenAndRequiredFields_recordsDeploymentAndReturns202Accepted() throws Exception {
		mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("RECORDED"))
				.andExpect(jsonPath("$.service").value("Payments"))
				.andExpect(jsonPath("$.version").value("v1.2.3"))
				.andExpect(jsonPath("$.commit").value("abcdef0123456789"))
				.andExpect(jsonPath("$.author").value("Alice Developer"))
				.andExpect(jsonPath("$.deployedAt").value("2026-11-09T10:00:00Z"))
				.andExpect(jsonPath("$.id").isNumber());

		List<Deployment> recorded = deployments.findAll();
		assertThat(recorded).hasSize(1);
		Deployment d = recorded.getFirst();
		assertThat(d.getService().getName()).isEqualTo("Payments");
		assertThat(d.getVersion()).isEqualTo("v1.2.3");
		assertThat(d.getCommit()).isEqualTo("abcdef0123456789");
		assertThat(d.getAuthor()).isEqualTo("Alice Developer");
		assertThat(d.getDeployedAt()).isEqualTo(Instant.parse("2026-11-09T10:00:00Z"));
	}

	@Test
	void duplicateWebhook_returns202WithDuplicateStatusAndDoesNotInsertDuplicate() throws Exception {
		// First ingestion
		mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("RECORDED"));

		// Duplicate ingestion with same (service, version, deployedAt)
		mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("DUPLICATE"))
				.andExpect(jsonPath("$.message").value("Deployment already recorded"))
				.andExpect(jsonPath("$.service").value("Payments"))
				.andExpect(jsonPath("$.version").value("v1.2.3"));

		assertThat(deployments.count()).isEqualTo(1);
	}

	@Test
	void unknownService_returns400BadRequestWithFieldError() throws Exception {
		String payload = """
				{
					"service": "NonExistentService",
					"version": "v1.0.0",
					"commit": "123456",
					"author": "Bob",
					"deployedAt": "2026-11-09T11:00:00Z"
				}
				""";

		mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.service").value("Service not found"));

		assertThat(deployments.count()).isEqualTo(0);
	}

	@ParameterizedTest
	@ValueSource(strings = {"service", "version", "commit", "author", "deployedAt"})
	void missingRequiredField_returns400BadRequest(String missingField) throws Exception {
		String payloadWithoutField = removeFieldFromPayload(missingField);

		mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(payloadWithoutField))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors." + missingField).isNotEmpty());

		assertThat(deployments.count()).isEqualTo(0);
	}

	@Test
	void missingToken_returns401Unauthorized() throws Exception {
		mvc.perform(post("/webhooks/deploys")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Missing or malformed Authorization header"));

		assertThat(deployments.count()).isEqualTo(0);
	}

	@Test
	void wrongToken_returns401Unauthorized() throws Exception {
		mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer tt_invalid_token_1234567890abcdef")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Invalid tool token"));

		assertThat(deployments.count()).isEqualTo(0);
	}

	@Test
	void revokedToken_returns401Unauthorized() throws Exception {
		toolService.revokeTool(toolId, admin.getId());

		mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Tool token has been revoked"));

		assertThat(deployments.count()).isEqualTo(0);
	}

	@Test
	void userJwtToken_returns401UnauthorizedOnWebhook() throws Exception {
		mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isUnauthorized());

		assertThat(deployments.count()).isEqualTo(0);
	}

	@Test
	void getServiceDeployments_returnsDeploymentsNewestFirst() throws Exception {
		// Post deployment 1 at 10:00
		mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"service": "Payments",
									"version": "v1.0.0",
									"commit": "aaaa1111",
									"author": "Alice",
									"deployedAt": "2026-11-09T10:00:00Z"
								}
								"""))
				.andExpect(status().isAccepted());

		// Post deployment 2 at 12:00
		mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"service": "Payments",
									"version": "v1.1.0",
									"commit": "bbbb2222",
									"author": "Bob",
									"deployedAt": "2026-11-09T12:00:00Z"
								}
								"""))
				.andExpect(status().isAccepted());

		// Post deployment 3 at 11:00
		mvc.perform(post("/webhooks/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"service": "Payments",
									"version": "v1.0.1",
									"commit": "cccc3333",
									"author": "Charlie",
									"deployedAt": "2026-11-09T11:00:00Z"
								}
								"""))
				.andExpect(status().isAccepted());

		// Fetch deployments via GET /api/services/{id}/deploys
		mvc.perform(get("/api/services/" + payments.getId() + "/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].version").value("v1.1.0"))
				.andExpect(jsonPath("$[0].deployedAt").value("2026-11-09T12:00:00Z"))
				.andExpect(jsonPath("$[1].version").value("v1.0.1"))
				.andExpect(jsonPath("$[1].deployedAt").value("2026-11-09T11:00:00Z"))
				.andExpect(jsonPath("$[2].version").value("v1.0.0"))
				.andExpect(jsonPath("$[2].deployedAt").value("2026-11-09T10:00:00Z"));
	}

	@Test
	void getServiceDeployments_nonExistentService_returns404() throws Exception {
		mvc.perform(get("/api/services/999999/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void tokenNeverAppearsInLogs() throws Exception {
		Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
		listAppender.start();
		rootLogger.addAppender(listAppender);

		try {
			mvc.perform(post("/webhooks/deploys")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
							.contentType(MediaType.APPLICATION_JSON)
							.content(VALID_PAYLOAD))
					.andExpect(status().isAccepted());

			for (ILoggingEvent event : listAppender.list) {
				String formatted = event.getFormattedMessage();
				assertThat(formatted)
						.as("Log message must never contain the plaintext token")
						.doesNotContain(validToolToken);

				if (event.getArgumentArray() != null) {
					for (Object arg : event.getArgumentArray()) {
						if (arg != null) {
							assertThat(arg.toString())
									.as("Log argument must never contain the plaintext token")
									.doesNotContain(validToolToken);
						}
					}
				}
			}
		}
		finally {
			rootLogger.detachAppender(listAppender);
			listAppender.stop();
		}
	}

	private String removeFieldFromPayload(String fieldToRemove) {
		return switch (fieldToRemove) {
			case "service" -> """
					{
						"version": "v1.2.3",
						"commit": "abcdef0123456789",
						"author": "Alice Developer",
						"deployedAt": "2026-11-09T10:00:00Z"
					}
					""";
			case "version" -> """
					{
						"service": "Payments",
						"commit": "abcdef0123456789",
						"author": "Alice Developer",
						"deployedAt": "2026-11-09T10:00:00Z"
					}
					""";
			case "commit" -> """
					{
						"service": "Payments",
						"version": "v1.2.3",
						"author": "Alice Developer",
						"deployedAt": "2026-11-09T10:00:00Z"
					}
					""";
			case "author" -> """
					{
						"service": "Payments",
						"version": "v1.2.3",
						"commit": "abcdef0123456789",
						"deployedAt": "2026-11-09T10:00:00Z"
					}
					""";
			case "deployedAt" -> """
					{
						"service": "Payments",
						"version": "v1.2.3",
						"commit": "abcdef0123456789",
						"author": "Alice Developer"
					}
					""";
			default -> VALID_PAYLOAD;
		};
	}

	private ServiceEntity createService(String name) {
		ServiceEntity service = new ServiceEntity(name, "Test service", admin, Criticality.HIGH, clock.instant());
		service.putTarget(Severity.SEV2, 60, 480);
		return services.saveAndFlush(service);
	}

	@Test
	@DisplayName("TC-FR-20.1c: 2-hour window boundary values (2h 1s before excluded, 2h before and 1s before included)")
	void incidentDeployWindow_boundaryValues_tcFr20_1c() throws Exception {
		Instant incidentCreatedAt = Instant.parse("2026-11-09T12:00:00Z");

		// 1. 2 hours and 1 second before -> EXCLUDED (09:59:59Z)
		deployments.saveAndFlush(new Deployment(payments, "v0.9.0", "hash1", "Author 1",
				incidentCreatedAt.minus(Duration.ofHours(2)).minusSeconds(1)));

		// 2. Exactly 2 hours before -> INCLUDED (10:00:00Z)
		deployments.saveAndFlush(new Deployment(payments, "v1.0.0", "hash2", "Author 2",
				incidentCreatedAt.minus(Duration.ofHours(2))));

		// 3. 1 second before -> INCLUDED (11:59:59Z)
		deployments.saveAndFlush(new Deployment(payments, "v1.1.0", "hash3", "Author 3",
				incidentCreatedAt.minusSeconds(1)));

		// 4. Future deploy: 1 second after incident creation -> EXCLUDED (12:00:01Z)
		deployments.saveAndFlush(new Deployment(payments, "v1.2.0", "hash4", "Author 4",
				incidentCreatedAt.plusSeconds(1)));

		// 5. Deploy for another service at 11:00:00Z -> EXCLUDED
		ServiceEntity otherService = createService("Inventory");
		deployments.saveAndFlush(new Deployment(otherService, "v1.0.0-inv", "hash5", "Author 5",
				incidentCreatedAt.minus(Duration.ofHours(1))));

		Incident incident = new Incident("Checkout outage", "Desc", payments, Severity.SEV1,
				IncidentStatus.OPEN, admin, incidentCreatedAt, 15, 240);
		incident.assignNumber(1001L);
		incidents.saveAndFlush(incident);

		mvc.perform(get("/api/incidents/" + incident.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deploys").isArray())
				.andExpect(jsonPath("$.deploys.length()").value(2))
				.andExpect(jsonPath("$.deploys[0].version").value("v1.1.0"))
				.andExpect(jsonPath("$.deploys[0].commit").value("hash3"))
				.andExpect(jsonPath("$.deploys[0].deployedAt").value("2026-11-09T11:59:59Z"))
				.andExpect(jsonPath("$.deploys[1].version").value("v1.0.0"))
				.andExpect(jsonPath("$.deploys[1].commit").value("hash2"))
				.andExpect(jsonPath("$.deploys[1].deployedAt").value("2026-11-09T10:00:00Z"));
	}

	@Test
	void incidentDeployWindow_emptyWhenNoDeploys() throws Exception {
		Incident incident = new Incident("No deploys incident", "Desc", payments, Severity.SEV3,
				IncidentStatus.OPEN, admin, clock.instant(), 240, 2880);
		incident.assignNumber(1002L);
		incidents.saveAndFlush(incident);

		mvc.perform(get("/api/incidents/" + incident.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deploys").isArray())
				.andExpect(jsonPath("$.deploys.length()").value(0));
	}

	@Test
	@DisplayName("TC-FR-20.1d: Link a deploy to an incident writes one timeline entry")
	void linkDeployment_adminCanLinkAndCreatesTimelineEntry() throws Exception {
		Deployment dep = deployments.saveAndFlush(new Deployment(payments, "v2.0.0", "commit200", "Dev A", clock.instant()));
		Incident incident = new Incident("Link Incident", "Desc", payments, Severity.SEV2, IncidentStatus.OPEN, admin, clock.instant(), 60, 480);
		incident.assignNumber(1003L);
		incidents.saveAndFlush(incident);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/deploys/" + dep.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventType").value("DEPLOY_LINKED"))
				.andExpect(jsonPath("$.actor").value("Deploy Admin"))
				.andExpect(jsonPath("$.details").value("Linked deployment v2.0.0 (commit200) for Payments"));

		mvc.perform(get("/api/incidents/" + incident.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.timeline[?(@.eventType == 'DEPLOY_LINKED')].details")
						.value("Linked deployment v2.0.0 (commit200) for Payments"));
	}

	@Test
	void linkDeployment_sameServiceSucceeds() throws Exception {
		Deployment dep = deployments.saveAndFlush(new Deployment(payments, "v2.0.1", "commitSameService", "Dev A", clock.instant()));
		Incident incident = new Incident("Same Service Incident", "Desc", payments, Severity.SEV2, IncidentStatus.OPEN, admin, clock.instant(), 60, 480);
		incident.assignNumber(1010L);
		incidents.saveAndFlush(incident);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/deploys/" + dep.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventType").value("DEPLOY_LINKED"))
				.andExpect(jsonPath("$.details").value("Linked deployment v2.0.1 (commitSameService) for Payments"));
	}

	@Test
	void linkDeployment_engineerCanLink() throws Exception {
		Deployment dep = deployments.saveAndFlush(new Deployment(payments, "v2.1.0", "commit210", "Dev B", clock.instant()));
		Incident incident = new Incident("Eng Link Incident", "Desc", payments, Severity.SEV2, IncidentStatus.OPEN, admin, clock.instant(), 60, 480);
		incident.assignNumber(1004L);
		incidents.saveAndFlush(incident);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/deploys/" + dep.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventType").value("DEPLOY_LINKED"))
				.andExpect(jsonPath("$.actor").value("Deploy Engineer"));
	}

	@Test
	void linkDeployment_viewerForbidden() throws Exception {
		Deployment dep = deployments.saveAndFlush(new Deployment(payments, "v2.2.0", "commit220", "Dev C", clock.instant()));
		Incident incident = new Incident("Viewer Link Incident", "Desc", payments, Severity.SEV2, IncidentStatus.OPEN, admin, clock.instant(), 60, 480);
		incident.assignNumber(1005L);
		incidents.saveAndFlush(incident);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/deploys/" + dep.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerJwt))
				.andExpect(status().isForbidden());
	}

	@Test
	void linkDeployment_unauthenticatedReturns401() throws Exception {
		mvc.perform(post("/api/incidents/999/deploys/999"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void linkDeployment_missingIncidentReturns404() throws Exception {
		Deployment dep = deployments.saveAndFlush(new Deployment(payments, "v2.3.0", "commit230", "Dev D", clock.instant()));

		mvc.perform(post("/api/incidents/999999/deploys/" + dep.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Incident 999999 not found"));
	}

	@Test
	void linkDeployment_missingDeploymentReturns404() throws Exception {
		Incident incident = new Incident("Missing Dep Incident", "Desc", payments, Severity.SEV2, IncidentStatus.OPEN, admin, clock.instant(), 60, 480);
		incident.assignNumber(1006L);
		incidents.saveAndFlush(incident);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/deploys/999999")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Deployment 999999 not found"));
	}

	@Test
	void linkDeployment_duplicateLinkingIsIdempotentAndWritesOneTimelineEntry() throws Exception {
		Deployment dep = deployments.saveAndFlush(new Deployment(payments, "v2.4.0", "commit240", "Dev E", clock.instant()));
		Incident incident = new Incident("Dupe Link Incident", "Desc", payments, Severity.SEV2, IncidentStatus.OPEN, admin, clock.instant(), 60, 480);
		incident.assignNumber(1007L);
		incidents.saveAndFlush(incident);

		// First call
		mvc.perform(post("/api/incidents/" + incident.getId() + "/deploys/" + dep.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
				.andExpect(status().isOk());

		// Second call (idempotent repeat)
		mvc.perform(post("/api/incidents/" + incident.getId() + "/deploys/" + dep.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt))
				.andExpect(status().isOk());

		var timeline = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		long deployLinkedCount = timeline.stream().filter(t -> t.getEventType() == TimelineEventType.DEPLOY_LINKED).count();
		assertThat(deployLinkedCount).isEqualTo(1);
	}

	@Test
	void linkDeployment_allowedOnClosedIncidentWithoutChangingStatusOrVersion() throws Exception {
		Deployment dep = deployments.saveAndFlush(new Deployment(payments, "v2.5.0", "commit250", "Dev F", clock.instant()));
		Incident incident = new Incident("Closed Incident", "Desc", payments, Severity.SEV2, IncidentStatus.CLOSED, admin, clock.instant(), 60, 480);
		incident.assignNumber(1008L);
		incidents.saveAndFlush(incident);
		Long initialVersion = incident.getVersion();

		mvc.perform(post("/api/incidents/" + incident.getId() + "/deploys/" + dep.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventType").value("DEPLOY_LINKED"));

		Incident reloaded = incidents.findById(incident.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.CLOSED);
		assertThat(reloaded.getVersion()).isEqualTo(initialVersion);
	}

	@Test
	void linkDeployment_crossServiceAllowed() throws Exception {
		ServiceEntity upstream = createService("UpstreamAuth");
		Deployment dep = deployments.saveAndFlush(new Deployment(upstream, "v3.0.0", "upstreamCommit", "Dev G", clock.instant()));
		Incident incident = new Incident("Downstream Incident", "Desc", payments, Severity.SEV2, IncidentStatus.OPEN, admin, clock.instant(), 60, 480);
		incident.assignNumber(1009L);
		incidents.saveAndFlush(incident);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/deploys/" + dep.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.details").value("Linked deployment v3.0.0 (upstreamCommit) for UpstreamAuth"));
	}
}
