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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Grafana alerts into intake (#88, D36). The bodies are what the "settle.alert" template in
 * grafana/contact-points/webhook-contact-point.yaml makes of a firing alert.
 */
@IntegrationTest
class GrafanaAlertIntakeTest {

	/**
	 * Recorded on 2026-11-12 from Grafana 12.2.0: the provisioned "Checkout is down" rule fired with both
	 * locations failing, and the settle-down contact point posted this body, unchanged.
	 */
	private static final String RECORDED_CHECKOUT_DOWN = """
			{"correlationKey":"grafana-down-checkout","description":"At least 2 locations failed the checkout check on 2 checks in a row.","details":{"fingerprint":"04764fbbc0a18b6b","rule":"Checkout is down","ruleType":"down"},"eventId":"gf-04764fbbc0a18b6b-1794485960","service":"checkout","severity":"SEV1","source":"grafana-down","title":"Checkout is down"}""";

	/** The same rule firing again later: a new start time, so a new eventId, and the same correlation key. */
	private static final String CHECKOUT_DOWN_NEXT_OUTAGE = RECORDED_CHECKOUT_DOWN
			.replace("gf-04764fbbc0a18b6b-1794485960", "gf-04764fbbc0a18b6b-1794489560");

	/** The template's body for the payments Degraded rule. */
	private static final String PAYMENTS_DEGRADED = """
			{"correlationKey":"grafana-degraded-payments","description":"At least 2 locations took longer than 2 s on the payments check, 3 checks in a row.","details":{"fingerprint":"7d1f3a0c9b2e4f61","rule":"Payments is slow","ruleType":"degraded"},"eventId":"gf-7d1f3a0c9b2e4f61-1794485960","service":"payments","severity":"SEV2","source":"grafana-degraded","title":"Payments is slow"}""";

	/** The template's body for the payments Error rate rule. */
	private static final String PAYMENTS_ERROR_RATE = """
			{"correlationKey":"grafana-error_rate-payments","description":"More than 5% of payments requests returned 5xx for 5 minutes.","details":{"fingerprint":"c2a95e7d41b08f33","rule":"Payments error rate above 5%","ruleType":"error_rate"},"eventId":"gf-c2a95e7d41b08f33-1794485960","service":"payments","severity":"SEV2","source":"grafana-error_rate","title":"Payments error rate above 5%"}""";

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ToolService toolService;

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

	// One tool, and one token, per rule type, as in the contact points.
	private String downToken;

	private String degradedToken;

	private String errorRateToken;

	@BeforeEach
	void setUp() {
		AppUser admin = accounts.save(
				new AppUser("grafana_admin@test.local", "Grafana Admin",
						passwordEncoder.encode("DevPassw0rd!"), clock.instant()),
				Role.ADMIN);

		for (String name : new String[] {"Payments", "Checkout"}) {
			ServiceEntity service = new ServiceEntity(name, name + " service", admin, Criticality.HIGH, clock.instant());
			service.putTarget(Severity.SEV1, 15, 120);
			service.putTarget(Severity.SEV2, 30, 240);
			services.saveAndFlush(service);
		}

		downToken = toolService.createTool(new CreateToolRequest("Grafana Down"), admin.getId()).token();
		degradedToken = toolService.createTool(new CreateToolRequest("Grafana Degraded"), admin.getId()).token();
		errorRateToken = toolService.createTool(new CreateToolRequest("Grafana Error rate"), admin.getId()).token();
	}

	@Test
	void recordedDownAlertOpensASev1Incident() throws Exception {
		send(downToken, RECORDED_CHECKOUT_DOWN)
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.eventId").value("gf-04764fbbc0a18b6b-1794485960"))
				.andExpect(jsonPath("$.source").value("grafana-down"));

		assertThat(alerts.count()).isEqualTo(1);
		assertThat(incidents.findAll())
				.singleElement()
				.satisfies(incident -> {
					assertThat(incident.getSeverity()).isEqualTo(Severity.SEV1);
					assertThat(incident.getService().getName()).isEqualTo("Checkout");
				});
	}

	/** TC-D36g: Grafana repeats a firing alert with the same body every repeat_interval. */
	@Test
	void sameFiringAlertPostedTwiceIsCountedOnce() throws Exception {
		send(downToken, RECORDED_CHECKOUT_DOWN).andExpect(status().isAccepted());
		send(downToken, RECORDED_CHECKOUT_DOWN).andExpect(status().isAccepted());

		assertThat(alerts.count()).isEqualTo(1);
		assertThat(incidents.count()).isEqualTo(1);
	}

	@Test
	void nextOutageWhileTheIncidentIsOpenJoinsIt() throws Exception {
		send(downToken, RECORDED_CHECKOUT_DOWN).andExpect(status().isAccepted());
		send(downToken, CHECKOUT_DOWN_NEXT_OUTAGE).andExpect(status().isAccepted());

		assertThat(alerts.count()).isEqualTo(2);
		Incident incident = incidents.findAll().getFirst();
		assertThat(incidents.count()).isEqualTo(1);
		assertThat(alerts.findAll())
				.extracting(alert -> alert.getIncident().getId())
				.containsOnly(incident.getId());
	}

	@Test
	void degradedAlertForAnotherServiceOpensItsOwnIncident() throws Exception {
		send(downToken, RECORDED_CHECKOUT_DOWN).andExpect(status().isAccepted());
		send(degradedToken, PAYMENTS_DEGRADED).andExpect(status().isAccepted());

		assertThat(alerts.count()).isEqualTo(2);
		assertThat(incidents.findAll())
				.extracting(Incident::getSeverity)
				.containsExactlyInAnyOrder(Severity.SEV1, Severity.SEV2);
	}

	@Test
	void errorRateAlertIsAccepted() throws Exception {
		send(errorRateToken, PAYMENTS_ERROR_RATE)
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.source").value("grafana-error_rate"));

		assertThat(alerts.count()).isEqualTo(1);
		assertThat(incidents.count()).isEqualTo(1);
	}

	@Test
	void alertWithoutAToolTokenIsRefused() throws Exception {
		mvc.perform(post("/webhooks/alerts")
						.contentType(MediaType.APPLICATION_JSON)
						.content(RECORDED_CHECKOUT_DOWN))
				.andExpect(status().isUnauthorized());

		assertThat(alerts.count()).isZero();
		assertThat(incidents.count()).isZero();
	}

	private ResultActions send(String token, String body) throws Exception {
		return mvc.perform(post("/webhooks/alerts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}
}
