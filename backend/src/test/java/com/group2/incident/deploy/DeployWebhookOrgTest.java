package com.group2.incident.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
class DeployWebhookOrgTest {

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private SignedUp org1;
	private SignedUp org2;

	@AfterEach
	void deleteOrgs() {
		if (org1 != null) {
			orgs.delete(org1.orgId());
		}
		if (org2 != null) {
			orgs.delete(org2.orgId());
		}
	}

	@Test
	void aDeploymentLandsInTheToolsOrg() throws Exception {
		long callerOrg = OrgContext.current();
		org1 = orgs.signUp("deploy-admin1@org1.test", "Deploy Org 1");
		orgs.createService(org1.adminToken(), "Payments", org1.adminId());

		String tool = mvc.perform(post("/api/tools").header(HttpHeaders.AUTHORIZATION, "Bearer " + org1.adminToken())
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"GitHub Actions\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String toolToken = JsonPath.read(tool, "$.token");

		mvc.perform(post("/webhooks/deploys").header(HttpHeaders.AUTHORIZATION, "Bearer " + toolToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"service": "Payments",
									"version": "v1.0.0",
									"commit": "abcdef1234567890",
									"author": "Alice Developer",
									"deployedAt": "2026-11-09T10:00:00Z"
								}
								"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("RECORDED"));

		Map<String, Object> saved = jdbc.queryForMap("""
				SELECT d.org_id AS deploy_org, s.org_id AS service_org
				FROM deployment d JOIN service s ON s.id = d.service_id
				WHERE d.version = 'v1.0.0'""");
		assertThat(((Number) saved.get("deploy_org")).longValue()).isEqualTo(org1.orgId());
		assertThat(((Number) saved.get("service_org")).longValue()).isEqualTo(org1.orgId());

		// The request's org does not leak back into the caller's thread.
		assertThat(OrgContext.current()).isEqualTo(callerOrg);
	}

	@Test
	void toolCannotTargetServiceInAnotherOrg() throws Exception {
		org1 = orgs.signUp("deploy-admin1@org1.test", "Deploy Org 1");
		orgs.createService(org1.adminToken(), "PaymentsOrg1", org1.adminId());

		org2 = orgs.signUp("deploy-admin2@org2.test", "Deploy Org 2");
		orgs.createService(org2.adminToken(), "CheckoutOrg2", org2.adminId());

		String tool2 = mvc.perform(post("/api/tools").header(HttpHeaders.AUTHORIZATION, "Bearer " + org2.adminToken())
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Org2 Bot\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String tool2Token = JsonPath.read(tool2, "$.token");

		// Tool 2 targets Org 1's service "PaymentsOrg1" -> fails with 400 Bad Request ("Service not found")
		mvc.perform(post("/webhooks/deploys").header(HttpHeaders.AUTHORIZATION, "Bearer " + tool2Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"service": "PaymentsOrg1",
									"version": "v1.0.0",
									"commit": "abcdef1234567890",
									"author": "Bob Developer",
									"deployedAt": "2026-11-09T10:00:00Z"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.service").value("Service not found"));
	}

	@Test
	void userInOrgCannotViewDeploysOfAnotherOrg() throws Exception {
		org1 = orgs.signUp("deploy-admin1@org1.test", "Deploy Org 1");
		long s1Id = orgs.createService(org1.adminToken(), "Service1", org1.adminId());

		org2 = orgs.signUp("deploy-admin2@org2.test", "Deploy Org 2");
		long s2Id = orgs.createService(org2.adminToken(), "Service2", org2.adminId());

		String tool1 = mvc.perform(post("/api/tools").header(HttpHeaders.AUTHORIZATION, "Bearer " + org1.adminToken())
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Org1 CI\"}"))
				.andReturn().getResponse().getContentAsString();
		String tool1Token = JsonPath.read(tool1, "$.token");

		// Record deploy on Service1 in Org 1
		mvc.perform(post("/webhooks/deploys").header(HttpHeaders.AUTHORIZATION, "Bearer " + tool1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"service": "Service1",
									"version": "v1.0.0",
									"commit": "abcdef1234567890",
									"author": "Alice",
									"deployedAt": "2026-11-09T10:00:00Z"
								}
								"""))
				.andExpect(status().isAccepted());

		// Org 1 admin can view deploys of Service1
		mvc.perform(get("/api/services/" + s1Id + "/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org1.adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		// Org 2 admin querying Service1 gets 404 Not Found
		mvc.perform(get("/api/services/" + s1Id + "/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org2.adminToken()))
				.andExpect(status().isNotFound());

		// Org 2 admin querying Service2 gets 0 deploys
		mvc.perform(get("/api/services/" + s2Id + "/deploys")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org2.adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void incidentDeploymentWindowDoesNotLeakCrossOrgDeploys() throws Exception {
		org1 = orgs.signUp("deploy-org1-window@test.local", "Org 1 Window");
		long s1Id = orgs.createService(org1.adminToken(), "Checkout", org1.adminId());

		org2 = orgs.signUp("deploy-org2-window@test.local", "Org 2 Window");
		long s2Id = orgs.createService(org2.adminToken(), "Checkout", org2.adminId());

		String tool1 = mvc.perform(post("/api/tools").header(HttpHeaders.AUTHORIZATION, "Bearer " + org1.adminToken())
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Org1 Tool\"}"))
				.andReturn().getResponse().getContentAsString();
		String tool1Token = JsonPath.read(tool1, "$.token");

		// Record deploy in Org 1
		mvc.perform(post("/webhooks/deploys").header(HttpHeaders.AUTHORIZATION, "Bearer " + tool1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"service": "Checkout",
									"version": "v1.0.0-org1",
									"commit": "abcdef1234567890",
									"author": "Alice",
									"deployedAt": "2026-11-09T10:00:00Z"
								}
								"""))
				.andExpect(status().isAccepted());

		// Declare incident in Org 2
		String incResp = orgs.declareIncident(org2.adminToken(), s2Id, "Org 2 Checkout Outage");
		int inc2Id = JsonPath.read(incResp, "$.id");

		// Org 2 incident must not contain Org 1 deploys
		mvc.perform(get("/api/incidents/" + inc2Id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org2.adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deploys.length()").value(0));
	}

	@Test
	void manualLinkingEnforcesCrossOrgIsolation() throws Exception {
		org1 = orgs.signUp("deploy-org1-link@test.local", "Org 1 Link");
		long s1Id = orgs.createService(org1.adminToken(), "S1", org1.adminId());

		org2 = orgs.signUp("deploy-org2-link@test.local", "Org 2 Link");
		long s2Id = orgs.createService(org2.adminToken(), "S2", org2.adminId());

		String tool1 = mvc.perform(post("/api/tools").header(HttpHeaders.AUTHORIZATION, "Bearer " + org1.adminToken())
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Tool 1\"}"))
				.andReturn().getResponse().getContentAsString();
		String tool1Token = JsonPath.read(tool1, "$.token");

		String tool2 = mvc.perform(post("/api/tools").header(HttpHeaders.AUTHORIZATION, "Bearer " + org2.adminToken())
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Tool 2\"}"))
				.andReturn().getResponse().getContentAsString();
		String tool2Token = JsonPath.read(tool2, "$.token");

		// Record deploy 1 in Org 1
		String d1Resp = mvc.perform(post("/webhooks/deploys").header(HttpHeaders.AUTHORIZATION, "Bearer " + tool1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"service": "S1",
									"version": "v1.0.0",
									"commit": "abcdef1234567890",
									"author": "Alice",
									"deployedAt": "2026-11-09T10:00:00Z"
								}
								"""))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		int d1Id = JsonPath.read(d1Resp, "$.id");

		// Record deploy 2 in Org 2
		String d2Resp = mvc.perform(post("/webhooks/deploys").header(HttpHeaders.AUTHORIZATION, "Bearer " + tool2Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
									"service": "S2",
									"version": "v2.0.0",
									"commit": "1234567890abcdef",
									"author": "Bob",
									"deployedAt": "2026-11-09T10:00:00Z"
								}
								"""))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		int d2Id = JsonPath.read(d2Resp, "$.id");

		// Declare incident 1 in Org 1, incident 2 in Org 2
		String inc1Resp = orgs.declareIncident(org1.adminToken(), s1Id, "Incident 1");
		int inc1Id = JsonPath.read(inc1Resp, "$.id");

		String inc2Resp = orgs.declareIncident(org2.adminToken(), s2Id, "Incident 2");
		int inc2Id = JsonPath.read(inc2Resp, "$.id");

		// Org 2 admin linking Org 1's deploy to Org 2's incident -> 404 (deploy not found)
		mvc.perform(post("/api/incidents/" + inc2Id + "/deploys/" + d1Id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org2.adminToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Deployment " + d1Id + " not found"));

		// Org 2 admin linking Org 2's deploy to Org 1's incident -> 404 (incident not found)
		mvc.perform(post("/api/incidents/" + inc1Id + "/deploys/" + d2Id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org2.adminToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Incident " + inc1Id + " not found"));

		// Org 1 admin linking Org 1's deploy to Org 1's incident -> 200 OK
		mvc.perform(post("/api/incidents/" + inc1Id + "/deploys/" + d1Id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org1.adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventType").value("DEPLOY_LINKED"));
	}
}
