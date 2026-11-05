package com.group2.incident.alert.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The webhook has no user token: the tool token decides the org (D48). Runs outside the test transaction,
 * so the request opens its session in the tool's org, as in production.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlertWebhookOrgTest {

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private SignedUp other;

	@AfterEach
	void deleteOrg() {
		if (other != null) {
			orgs.delete(other.orgId());
		}
	}

	@Test
	void anAlertAndItsIncidentLandInTheToolsOrg() throws Exception {
		long callerOrg = OrgContext.current();
		other = orgs.signUp("webhook-admin@other.test", "Webhook Org");
		orgs.createService(other.adminToken(), "Payments", other.adminId());
		String tool = mvc.perform(post("/api/tools").header(HttpHeaders.AUTHORIZATION, "Bearer " + other.adminToken())
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Prometheus\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		mvc.perform(post("/webhooks/alerts").header(HttpHeaders.AUTHORIZATION, "Bearer " + JsonPath.read(tool, "$.token"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"source": "prometheus", "eventId": "org-evt-1", "service": "Payments", "title": "High error rate", "description": "500s on /pay",
								 "severity": "SEV2", "correlationKey": "pay-5xx"}
								"""))
				.andExpect(status().isAccepted());

		Map<String, Object> saved = jdbc.queryForMap("""
				SELECT a.org_id AS alert_org, i.org_id AS incident_org
				FROM alert a JOIN incident i ON i.id = a.incident_id
				WHERE a.event_id = 'org-evt-1'""");
		assertThat(((Number) saved.get("alert_org")).longValue()).isEqualTo(other.orgId());
		assertThat(((Number) saved.get("incident_org")).longValue()).isEqualTo(other.orgId());
		// The request's org does not leak back into the caller's thread.
		assertThat(OrgContext.current()).isEqualTo(callerOrg);
	}

}
