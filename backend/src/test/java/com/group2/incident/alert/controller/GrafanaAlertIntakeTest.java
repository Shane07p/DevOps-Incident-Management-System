package com.group2.incident.alert.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.alert.dto.CreateToolRequest;
import com.group2.incident.alert.dto.CreateToolResponse;
import com.group2.incident.alert.repository.AlertRepository;
import com.group2.incident.alert.service.ToolService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TC-D36g: Intake test with a recorded Grafana alerting payload.
 *
 * Verifies that a realistic Grafana Cloud alert, transformed by the custom
 * payload template defined in grafana/contact-points/webhook-contact-point.yaml,
 * is accepted by /webhooks/alerts and creates or correlates to an incident.
 */
@IntegrationTest
class GrafanaAlertIntakeTest {

	/**
	 * Recorded Grafana webhook payload after payload-template transformation.
	 * This is what our contact point sends after evaluating the Go template
	 * against a real "Down" firing alert for the Payments endpoint.
	 */
	private static final String GRAFANA_DOWN_ALERT_PAYLOAD = """
			{
				"source": "grafana-down",
				"eventId": "gf-abc123def456",
				"service": "Payments",
				"title": "Payments endpoint is down",
				"description": "Synthetic monitoring probes in Frankfurt and New York both failed to reach the payments authorization endpoint.",
				"severity": "SEV1",
				"correlationKey": "grafana-down-payments",
				"details": {
					"grafana_fingerprint": "abc123def456",
					"grafana_status": "firing",
					"rule_type": "down",
					"grafana_rule_uid": "demo-shop-down-payments"
				}
			}
			""";

	/**
	 * A second "Down" firing event for the same service, same correlation key.
	 * This must join the same incident as the first one.
	 */
	private static final String GRAFANA_DOWN_ALERT_REPEAT = """
			{
				"source": "grafana-down",
				"eventId": "gf-abc123def789",
				"service": "Payments",
				"title": "Payments endpoint is down",
				"description": "Synthetic monitoring probes still failing for the payments endpoint.",
				"severity": "SEV1",
				"correlationKey": "grafana-down-payments",
				"details": {
					"grafana_fingerprint": "abc123def789",
					"grafana_status": "firing",
					"rule_type": "down",
					"grafana_rule_uid": "demo-shop-down-payments"
				}
			}
			""";

	/**
	 * A "Degraded" warning alert for Checkout — different service, different
	 * correlation key, so it must create a separate incident.
	 */
	private static final String GRAFANA_DEGRADED_ALERT_PAYLOAD = """
			{
				"source": "grafana-degraded",
				"eventId": "gf-deg456checkout",
				"service": "Checkout",
				"title": "Checkout endpoint is degraded (slow)",
				"description": "Synthetic probes are seeing elevated latency on the checkout endpoint.",
				"severity": "SEV2",
				"correlationKey": "grafana-degraded-checkout",
				"details": {
					"grafana_fingerprint": "deg456checkout",
					"grafana_status": "firing",
					"rule_type": "degraded",
					"grafana_rule_uid": "demo-shop-degraded-checkout"
				}
			}
			""";

	/**
	 * An "Error Rate" warning alert for Payments.
	 */
	private static final String GRAFANA_ERROR_RATE_ALERT_PAYLOAD = """
			{
				"source": "grafana-error_rate",
				"eventId": "gf-err789payments",
				"service": "Payments",
				"title": "Payments error rate above 5 %%",
				"description": "The ratio of 5xx responses to total requests on the payments service exceeds the threshold.",
				"severity": "SEV2",
				"correlationKey": "grafana-error_rate-payments",
				"details": {
					"grafana_fingerprint": "err789payments",
					"grafana_status": "firing",
					"rule_type": "error_rate",
					"grafana_rule_uid": "demo-shop-error-rate-payments"
				}
			}
			""";

	@Autowired private TestAccounts accounts;
	@Autowired private MockMvc mvc;
	@Autowired private ToolService toolService;
	@Autowired private ServiceRepository services;
	@Autowired private AlertRepository alerts;
	@Autowired private IncidentRepository incidents;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private Clock clock;

	private String toolToken;

	@BeforeEach
	void setUp() {
		AppUser admin = accounts.save(
				new AppUser("grafana_admin@test.local", "Grafana Admin",
						passwordEncoder.encode("DevPassw0rd!"), clock.instant()),
				Role.ADMIN);

		ServiceEntity payments = new ServiceEntity("Payments", "Payment service", admin, Criticality.HIGH, clock.instant());
		payments.putTarget(Severity.SEV1, 15, 120);
		payments.putTarget(Severity.SEV2, 30, 240);
		services.saveAndFlush(payments);

		ServiceEntity checkout = new ServiceEntity("Checkout", "Checkout service", admin, Criticality.HIGH, clock.instant());
		checkout.putTarget(Severity.SEV1, 15, 120);
		checkout.putTarget(Severity.SEV2, 30, 240);
		services.saveAndFlush(checkout);

		CreateToolResponse tool = toolService.createTool(
				new CreateToolRequest("Grafana Alerting"), admin.getId());
		toolToken = tool.token();
	}

	@Test
	void grafanaDownAlert_isAcceptedAndCreatesIncident() throws Exception {
		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(GRAFANA_DOWN_ALERT_PAYLOAD))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.eventId").value("gf-abc123def456"))
				.andExpect(jsonPath("$.source").value("grafana-down"));

		assertThat(alerts.count()).isEqualTo(1);
		assertThat(incidents.count()).isEqualTo(1);
	}

	@Test
	void repeatedGrafanaDownAlert_joinsExistingIncident() throws Exception {
		// First alert creates the incident.
		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(GRAFANA_DOWN_ALERT_PAYLOAD))
				.andExpect(status().isAccepted());

		// Second alert with the same correlation key joins the same incident.
		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(GRAFANA_DOWN_ALERT_REPEAT))
				.andExpect(status().isAccepted());

		assertThat(alerts.count()).isEqualTo(2);
		assertThat(incidents.count()).isEqualTo(1);
		assertThat(alerts.findAll())
				.extracting(a -> a.getIncident().getId())
				.containsOnly(incidents.findAll().getFirst().getId());
	}

	@Test
	void grafanaDegradedAlertForDifferentService_createsSeparateIncident() throws Exception {
		// Down alert for Payments.
		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(GRAFANA_DOWN_ALERT_PAYLOAD))
				.andExpect(status().isAccepted());

		// Degraded alert for Checkout — different source, different service.
		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(GRAFANA_DEGRADED_ALERT_PAYLOAD))
				.andExpect(status().isAccepted());

		assertThat(alerts.count()).isEqualTo(2);
		assertThat(incidents.count()).isEqualTo(2);
	}

	@Test
	void grafanaErrorRateAlert_isAccepted() throws Exception {
		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(GRAFANA_ERROR_RATE_ALERT_PAYLOAD))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.eventId").value("gf-err789payments"))
				.andExpect(jsonPath("$.source").value("grafana-error_rate"));

		assertThat(alerts.count()).isEqualTo(1);
		assertThat(incidents.count()).isEqualTo(1);
	}

	@Test
	void grafanaAlertWithoutToken_returns401() throws Exception {
		mvc.perform(post("/webhooks/alerts")
						.contentType(MediaType.APPLICATION_JSON)
						.content(GRAFANA_DOWN_ALERT_PAYLOAD))
				.andExpect(status().isUnauthorized());

		assertThat(alerts.count()).isEqualTo(0);
		assertThat(incidents.count()).isEqualTo(0);
	}
}
