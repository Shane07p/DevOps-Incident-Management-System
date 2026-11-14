package com.group2.incident.alert.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.alert.dto.CreateToolRequest;
import com.group2.incident.alert.repository.AlertRepository;
import com.group2.incident.alert.service.ToolService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Grafana Alerting's own webhook format into intake (#260). The body follows the default payload in Grafana's
 * webhook notifier documentation; it is written here, not recorded from a Grafana.
 */
@IntegrationTest
class GrafanaWebhookTest {

	private static final String NOTIFICATION = """
			{
			  "receiver": "settle", "status": "firing", "orgId": 1,
			  "alerts": [
			    {
			      "status": "firing",
			      "labels": {"alertname": "Checkout errors", "service": "Checkout", "severity": "critical", "grafana_folder": "Shop"},
			      "annotations": {"summary": "Checkout 5xx above 5%", "description": "More than 5% of checkout requests failed"},
			      "startsAt": "2026-11-13T10:00:00Z", "endsAt": "0001-01-01T00:00:00Z",
			      "generatorURL": "https://grafana.example/alerting/grafana/abc/view",
			      "fingerprint": "a1b2c3d4e5f60718", "values": {"B": 0.07}, "valueString": "[ var='B' value=0.07 ]"
			    },
			    {
			      "status": "resolved",
			      "labels": {"alertname": "Search slow", "service": "Checkout"},
			      "annotations": {}, "startsAt": "2026-11-13T09:00:00Z", "endsAt": "2026-11-13T09:30:00Z",
			      "fingerprint": "0f0e0d0c0b0a0908"
			    }
			  ],
			  "groupLabels": {"alertname": "Checkout errors"}, "commonLabels": {"service": "Checkout"},
			  "commonAnnotations": {}, "externalURL": "https://grafana.example/", "version": "1",
			  "groupKey": "{}:{alertname=\\"Checkout errors\\"}", "truncatedAlerts": 0,
			  "title": "[FIRING:1] Checkout errors", "state": "alerting", "message": "..."
			}
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private ToolService toolService;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private AlertRepository alerts;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private Clock clock;

	private String toolToken;

	@BeforeEach
	void setUp() {
		AppUser admin = accounts.create("grafana-webhook-admin@test.local", Role.ADMIN);
		ServiceEntity checkout = new ServiceEntity("Checkout", "Checkout service", admin, Criticality.HIGH, clock.instant());
		checkout.putTarget(Severity.SEV1, 15, 120);
		checkout.putTarget(Severity.SEV2, 30, 240);
		services.saveAndFlush(checkout);
		toolToken = toolService.createTool(new CreateToolRequest("Grafana"), admin.getId()).token();
	}

	@Test
	void theFiringAlertOpensAnIncidentAndTheResolvedOneIsLeftOut() throws Exception {
		send(toolToken, NOTIFICATION)
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.accepted.length()").value(1))
				.andExpect(jsonPath("$.accepted[0].eventId").value("gf-a1b2c3d4e5f60718-1794564000"))
				.andExpect(jsonPath("$.skipped.length()").value(1))
				.andExpect(jsonPath("$.skipped[0].fingerprint").value("0f0e0d0c0b0a0908"))
				.andExpect(jsonPath("$.skipped[0].reason").value("not firing"));

		assertThat(alerts.count()).isEqualTo(1);
		assertThat(incidents.findAll()).singleElement().satisfies(incident -> {
			assertThat(incident.getSeverity()).isEqualTo(Severity.SEV1);
			assertThat(incident.getTitle()).isEqualTo("Checkout 5xx above 5%");
		});
	}

	@Test
	void grafanaRepeatingTheSameFiringAlertIsCountedOnce() throws Exception {
		send(toolToken, NOTIFICATION).andExpect(status().isAccepted());
		send(toolToken, NOTIFICATION.replace("\"values\": {\"B\": 0.07}", "\"values\": {\"B\": 0.09}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.accepted.length()").value(1));

		assertThat(alerts.count()).isEqualTo(1);
		assertThat(incidents.count()).isEqualTo(1);
	}

	@Test
	void anAlertForAServiceSettleDoesNotKnowIsSkippedWithTheReason() throws Exception {
		send(toolToken, NOTIFICATION.replace("\"service\": \"Checkout\", \"severity\"", "\"service\": \"Billing\", \"severity\""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.accepted.length()").value(0))
				.andExpect(jsonPath("$.skipped[0].reason").value(org.hamcrest.Matchers.containsString("Billing")));

		assertThat(alerts.count()).isZero();
		assertThat(incidents.findAll()).extracting(Incident::getTitle).isEmpty();
	}

	@Test
	void onlyARegisteredToolTokenGetsIn() throws Exception {
		mvc.perform(post("/webhooks/grafana").contentType(MediaType.APPLICATION_JSON).content(NOTIFICATION))
				.andExpect(status().isUnauthorized());
		send("tt_not_a_real_token", NOTIFICATION).andExpect(status().isUnauthorized());

		assertThat(alerts.count()).isZero();
	}

	private ResultActions send(String token, String body) throws Exception {
		return mvc.perform(post("/webhooks/grafana")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}
}
