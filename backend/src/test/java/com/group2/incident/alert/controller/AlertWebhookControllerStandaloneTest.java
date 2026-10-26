package com.group2.incident.alert.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.alert.dto.AlertAcceptedResponse;
import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.service.AlertIntakeService;
import com.group2.incident.common.error.ApiExceptionHandler;
import com.group2.incident.common.error.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AlertWebhookControllerStandaloneTest {

	private static final String VALID_PAYLOAD = """
			{
				"source": "prometheus",
				"eventId": "evt-555",
				"service": "Payments",
				"title": "Database connection spike",
				"description": "Active pool connections > 90%",
				"severity": "SEV1",
				"correlationKey": "db-spike",
				"details": { "active": 95 }
			}
			""";

	@Mock
	private AlertIntakeService alertIntakeService;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		AlertWebhookController controller = new AlertWebhookController(alertIntakeService);
		mvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
	}

	@Test
	void receiveAlert_validRequest_returns202Accepted() throws Exception {
		when(alertIntakeService.processAlert(eq("Bearer tt_valid"), any(AlertRequest.class)))
				.thenReturn(new AlertAcceptedResponse("ACCEPTED", "evt-555", "prometheus", "Alert accepted for processing"));

		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer tt_valid")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.eventId").value("evt-555"))
				.andExpect(jsonPath("$.source").value("prometheus"));
	}

	@Test
	void receiveAlert_missingAuthorizationHeader_returns401() throws Exception {
		when(alertIntakeService.processAlert(eq(null), any(AlertRequest.class)))
				.thenThrow(new UnauthorizedException("Missing or malformed Authorization header"));

		mvc.perform(post("/webhooks/alerts")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Missing or malformed Authorization header"));
	}

	@Test
	void receiveAlert_invalidToken_returns401() throws Exception {
		when(alertIntakeService.processAlert(eq("Bearer wrong"), any(AlertRequest.class)))
				.thenThrow(new UnauthorizedException("Invalid tool token"));

		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer wrong")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Invalid tool token"));
	}

	@Test
	void receiveAlert_revokedToken_returns401() throws Exception {
		when(alertIntakeService.processAlert(eq("Bearer revoked"), any(AlertRequest.class)))
				.thenThrow(new UnauthorizedException("Tool token has been revoked"));

		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer revoked")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Tool token has been revoked"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"source", "eventId", "service", "title", "description", "severity"})
	void receiveAlert_missingField_returns400(String missingField) throws Exception {
		String payloadWithoutField = removeFieldFromPayload(missingField);

		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer tt_valid")
						.contentType(MediaType.APPLICATION_JSON)
						.content(payloadWithoutField))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors." + missingField).isNotEmpty());
	}

	@Test
	void receiveAlert_invalidSeverity_returns400() throws Exception {
		String invalidSeverity = """
				{
					"source": "prometheus",
					"eventId": "evt-1",
					"service": "Payments",
					"title": "Title",
					"description": "Desc",
					"severity": "SEV5"
				}
				""";

		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer tt_valid")
						.contentType(MediaType.APPLICATION_JSON)
						.content(invalidSeverity))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.severity").isNotEmpty());
	}

	private String removeFieldFromPayload(String fieldToRemove) {
		return switch (fieldToRemove) {
			case "source" -> """
					{
						"eventId": "evt-1",
						"service": "Payments",
						"title": "Title",
						"description": "Desc",
						"severity": "SEV1"
					}
					""";
			case "eventId" -> """
					{
						"source": "prometheus",
						"service": "Payments",
						"title": "Title",
						"description": "Desc",
						"severity": "SEV1"
					}
					""";
			case "service" -> """
					{
						"source": "prometheus",
						"eventId": "evt-1",
						"title": "Title",
						"description": "Desc",
						"severity": "SEV1"
					}
					""";
			case "title" -> """
					{
						"source": "prometheus",
						"eventId": "evt-1",
						"service": "Payments",
						"description": "Desc",
						"severity": "SEV1"
					}
					""";
			case "description" -> """
					{
						"source": "prometheus",
						"eventId": "evt-1",
						"service": "Payments",
						"title": "Title",
						"severity": "SEV1"
					}
					""";
			case "severity" -> """
					{
						"source": "prometheus",
						"eventId": "evt-1",
						"service": "Payments",
						"title": "Title",
						"description": "Desc"
					}
					""";
			default -> VALID_PAYLOAD;
		};
	}
}
