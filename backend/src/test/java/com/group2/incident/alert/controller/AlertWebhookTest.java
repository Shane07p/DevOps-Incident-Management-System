package com.group2.incident.alert.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.alert.repository.AlertRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class AlertWebhookTest {

	private static final String VALID_PAYLOAD = """
			{
				"source": "prometheus",
				"eventId": "evt-123",
				"service": "Payments",
				"title": "High error rate",
				"description": "500 errors on /pay",
				"severity": "SEV2",
				"correlationKey": "pay-error-key",
				"details": { "metric": "http_requests_total" }
			}
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ToolService toolService;

	@Autowired
	private MonitoringToolRepository tools;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private AlertRepository alerts;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	private AppUser admin;
	private String validToolToken;
	private Long toolId;
	private ServiceEntity payments;

	@BeforeEach
	void setUp() {
		admin = users.saveAndFlush(new AppUser("webhook_admin", "Webhook Admin",
				passwordEncoder.encode("DevPassw0rd!"), Role.ADMIN, clock.instant()));
		payments = createService("Payments");

		CreateToolResponse created = toolService.createTool(new CreateToolRequest("Webhook Tool " + clock.millis()), admin.getId());
		validToolToken = created.token();
		toolId = created.id();
	}

	@Test
	void validTokenAndRequiredFields_returns202Accepted() throws Exception {
		// TC-FR-03.1b: An alert with a valid token and all required fields is accepted
		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.eventId").value("evt-123"))
				.andExpect(jsonPath("$.source").value("prometheus"));
	}

	@Test
	void repeatedEventWithSameCanonicalContent_isIdempotent() throws Exception {
		String original = """
				{
					"source": "prometheus",
					"eventId": "evt-idempotent",
					"service": "Payments",
					"title": "High error rate",
					"description": "500 errors on /pay",
					"severity": "SEV2",
					"correlationKey": "pay-error-key",
					"details": { "z": 2, "a": 1 },
					"receivedAt": "2026-10-27T10:00:00Z"
				}
				""";
		String retry = original
				.replace("\"z\": 2, \"a\": 1", "\"a\": 1, \"z\": 2")
				.replace("2026-10-27T10:00:00Z", "2026-10-27T10:05:00Z");

		mvc.perform(alertRequest(original)).andExpect(status().isAccepted());
		mvc.perform(alertRequest(retry)).andExpect(status().isAccepted());

		assertThat(alerts.count()).isEqualTo(1);
		assertThat(incidents.count()).isEqualTo(1);
	}

	@Test
	void repeatedEventWithChangedPayload_returnsConflict() throws Exception {
		mvc.perform(alertRequest(VALID_PAYLOAD)).andExpect(status().isAccepted());
		String changedPayload = VALID_PAYLOAD.replace("http_requests_total", "cpu_usage");

		mvc.perform(alertRequest(changedPayload))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));

		assertThat(alerts.count()).isEqualTo(1);
		assertThat(incidents.count()).isEqualTo(1);
	}

	@Test
	void alertsWithSameSourceServiceAndCorrelationKey_shareActiveIncident() throws Exception {
		mvc.perform(alertRequest(VALID_PAYLOAD)).andExpect(status().isAccepted());
		String second = VALID_PAYLOAD.replace("evt-123", "evt-124");

		mvc.perform(alertRequest(second)).andExpect(status().isAccepted());

		assertThat(alerts.count()).isEqualTo(2);
		assertThat(incidents.count()).isEqualTo(1);
		assertThat(alerts.findAll()).extracting(alert -> alert.getIncident().getId()).containsOnly(incidents.findAll().getFirst().getId());
	}

	@Test
	void sameCorrelationKeyFromDifferentSource_doesNotShareIncident() throws Exception {
		mvc.perform(alertRequest(VALID_PAYLOAD)).andExpect(status().isAccepted());
		String second = VALID_PAYLOAD
				.replace("evt-123", "evt-other-source")
				.replace("\"source\": \"prometheus\"", "\"source\": \"grafana\"");

		mvc.perform(alertRequest(second)).andExpect(status().isAccepted());

		assertThat(incidents.count()).isEqualTo(2);
		assertThat(alerts.findAll()).extracting(alert -> alert.getIncident().getId()).doesNotHaveDuplicates();
	}

	@Test
	void sameCorrelationKeyForDifferentService_doesNotShareIncident() throws Exception {
		createService("Orders");
		mvc.perform(alertRequest(VALID_PAYLOAD)).andExpect(status().isAccepted());
		String second = VALID_PAYLOAD
				.replace("evt-123", "evt-orders")
				.replace("\"service\": \"Payments\"", "\"service\": \"Orders\"");

		mvc.perform(alertRequest(second)).andExpect(status().isAccepted());

		assertThat(incidents.count()).isEqualTo(2);
		assertThat(alerts.findAll()).extracting(alert -> alert.getIncident().getId()).doesNotHaveDuplicates();
	}

	@Test
	void alertsWithoutCorrelationKey_createSeparateIncidents() throws Exception {
		String noKey = VALID_PAYLOAD.replace("\"correlationKey\": \"pay-error-key\",", "");
		mvc.perform(alertRequest(noKey)).andExpect(status().isAccepted());
		mvc.perform(alertRequest(noKey.replace("evt-123", "evt-no-key-2"))).andExpect(status().isAccepted());

		assertThat(incidents.count()).isEqualTo(2);
	}

	@Test
	void missingToken_returns401Unauthorized() throws Exception {
		// TC-FR-03.1c: Missing token gets 401; nothing created
		long toolCountBefore = tools.count();

		mvc.perform(post("/webhooks/alerts")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Missing or malformed Authorization header"));

		assertThat(tools.count()).isEqualTo(toolCountBefore);
	}

	@Test
	void wrongToken_returns401Unauthorized() throws Exception {
		// TC-FR-03.1c: Wrong token gets 401; nothing created
		long toolCountBefore = tools.count();

		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer tt_invalid_token_1234567890abcdef")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Invalid tool token"));

		assertThat(tools.count()).isEqualTo(toolCountBefore);
	}

	@Test
	void missingTokenAndInvalidBody_returns401BeforeValidation() throws Exception {
		// The token is checked before the body, so a caller without one learns nothing about the payload.
		mvc.perform(post("/webhooks/alerts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.errors").isEmpty());
	}

	@Test
	void malformedAuthorizationHeader_returns401Unauthorized() throws Exception {
		// Non-Bearer header
		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Basic " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Missing or malformed Authorization header"));
	}

	@Test
	void revokedToken_returns401Unauthorized() throws Exception {
		// TC-FR-03.1c: Revoked token gets 401; nothing created
		toolService.revokeTool(toolId, admin.getId());
		long toolCountBefore = tools.count();

		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Tool token has been revoked"));

		assertThat(tools.count()).isEqualTo(toolCountBefore);
	}

	@ParameterizedTest
	@ValueSource(strings = {"source", "eventId", "service", "title", "description", "severity"})
	void missingRequiredField_returns400BadRequest(String missingField) throws Exception {
		// TC-FR-03.1d: Each required field left out gets 400; nothing created
		String payloadWithoutField = removeFieldFromPayload(missingField);
		long toolCountBefore = tools.count();

		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(payloadWithoutField))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors." + missingField).isNotEmpty());

		assertThat(tools.count()).isEqualTo(toolCountBefore);
	}

	@Test
	void invalidSeverity_returns400BadRequest() throws Exception {
		String invalidSeverityPayload = """
				{
					"source": "prometheus",
					"eventId": "evt-123",
					"service": "Payments",
					"title": "High error rate",
					"description": "500 errors on /pay",
					"severity": "SEV99"
				}
				""";

		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(invalidSeverityPayload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.severity").isNotEmpty());
	}

	@Test
	void tokenNeverAppearsInLogs() throws Exception {
		// TC-NFR-04.2a: Register a tool and send alerts while capturing the logs; token never appears in logs
		Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
		listAppender.start();
		rootLogger.addAppender(listAppender);

		try {
			// Step 1: Register tool via API
			String loginResponse = mvc.perform(post("/api/auth/login")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"username\":\"webhook_admin\",\"password\":\"DevPassw0rd!\"}"))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			String adminJwt = JsonPath.read(loginResponse, "$.token");

			String createResponse = mvc.perform(post("/api/tools")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"name\":\"Log Check Tool\"}"))
					.andExpect(status().isCreated())
					.andReturn().getResponse().getContentAsString();

			String createdToken = JsonPath.read(createResponse, "$.token");

			// Step 2: Send alert to webhook using the created token
			mvc.perform(post("/webhooks/alerts")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + createdToken)
							.contentType(MediaType.APPLICATION_JSON)
							.content(VALID_PAYLOAD))
					.andExpect(status().isAccepted());

			// Step 3: Verify the token string NEVER appears in captured logs
			for (ILoggingEvent event : listAppender.list) {
				String formattedMessage = event.getFormattedMessage();
				assertThat(formattedMessage)
						.as("Log message must never contain the plaintext token")
						.doesNotContain(createdToken);

				if (event.getArgumentArray() != null) {
					for (Object arg : event.getArgumentArray()) {
						if (arg != null) {
							assertThat(arg.toString())
									.as("Log argument must never contain the plaintext token")
									.doesNotContain(createdToken);
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
			case "source" -> """
					{
						"eventId": "evt-123",
						"service": "Payments",
						"title": "High error rate",
						"description": "500 errors on /pay",
						"severity": "SEV2"
					}
					""";
			case "eventId" -> """
					{
						"source": "prometheus",
						"service": "Payments",
						"title": "High error rate",
						"description": "500 errors on /pay",
						"severity": "SEV2"
					}
					""";
			case "service" -> """
					{
						"source": "prometheus",
						"eventId": "evt-123",
						"title": "High error rate",
						"description": "500 errors on /pay",
						"severity": "SEV2"
					}
					""";
			case "title" -> """
					{
						"source": "prometheus",
						"eventId": "evt-123",
						"service": "Payments",
						"description": "500 errors on /pay",
						"severity": "SEV2"
					}
					""";
			case "description" -> """
					{
						"source": "prometheus",
						"eventId": "evt-123",
						"service": "Payments",
						"title": "High error rate",
						"severity": "SEV2"
					}
					""";
			case "severity" -> """
					{
						"source": "prometheus",
						"eventId": "evt-123",
						"service": "Payments",
						"title": "High error rate",
						"description": "500 errors on /pay"
					}
					""";
			default -> VALID_PAYLOAD;
		};
	}

	private MockHttpServletRequestBuilder alertRequest(String payload) {
		return post("/webhooks/alerts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToolToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload);
	}

	private ServiceEntity createService(String name) {
		ServiceEntity service = new ServiceEntity(name, "Test service", admin, Criticality.HIGH, clock.instant());
		service.putTarget(Severity.SEV2, 60, 480);
		return services.saveAndFlush(service);
	}
}
