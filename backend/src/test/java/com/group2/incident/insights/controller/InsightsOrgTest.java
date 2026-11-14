package com.group2.incident.insights.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.insights.service.InsightsService;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-tenant isolation for Insights API (D48). Runs outside the test transaction so
 * each request opens its session in its caller's org.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InsightsOrgTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private InsightsService insightsService;

	@Autowired
	private Clock clock;

	private SignedUp orgA;
	private SignedUp orgB;

	@AfterEach
	void tearDown() {
		ReflectionTestUtils.setField(insightsService, "clock", clock);
		if (orgA != null) {
			orgs.delete(orgA.orgId());
		}
		if (orgB != null) {
			orgs.delete(orgB.orgId());
		}
	}

	@Test
	@DisplayName("Cross-organization data isolation: insights never include another org's data")
	void crossOrganizationDataIsolation() throws Exception {
		// Signed up Org A
		orgA = orgs.signUp("org_a_admin@test.local", "Org A Insights");
		long serviceAId = orgs.createService(orgA.adminToken(), "Org A Payments", orgA.adminId());

		// Declare incident in Org A via API
		mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + orgA.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Org A Outage","description":"Details","serviceId":%d,"severity":"SEV1"}
								""".formatted(serviceAId)))
				.andExpect(status().isCreated());

		// Set insights clock just after incident creation
		ReflectionTestUtils.setField(insightsService, "clock", Clock.fixed(clock.instant().plusSeconds(60), ZoneOffset.UTC));

		// Read insights as Org A -> total 1
		mvc.perform(get("/api/insights")
						.queryParam("days", "7")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + orgA.adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidents.total").value(1))
				.andExpect(jsonPath("$.byService[0].name").value("Org A Payments"));

		// Now sign up Org B
		orgB = orgs.signUp("org_b_admin@test.local", "Org B Insights");

		// Read insights as Org B -> 0 incidents, empty byService!
		mvc.perform(get("/api/insights")
						.queryParam("days", "7")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + orgB.adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.incidents.total").value(0))
				.andExpect(jsonPath("$.byService").isEmpty())
				.andExpect(jsonPath("$.outOfHoursPages").value(0));
	}
}

