package com.group2.incident.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Whole orgs for tests that run outside the test transaction (D48): sign one up through the API, give it
 * a service and incidents, and delete everything it owns afterwards. Registered by {@link IntegrationTest}.
 */
public class TestOrgs {

	public static final String PASSWORD = "DevPassw0rd!";

	// Every table with org_id; service_sla_target goes with its service.
	private static final List<String> ORG_TABLES = List.of("notification", "outbox", "monitor_check_result", "uptime_monitor", "alert", "timeline_entry", "audit_entry",
			"escalation_task_step", "escalation_task", "escalation_step", "escalation_policy", "on_call_shift", "incident",
			"monitoring_tool", "service");

	private final MockMvc mvc;
	private final JdbcTemplate jdbc;
	private final PlatformTransactionManager transactionManager;

	public TestOrgs(MockMvc mvc, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.transactionManager = transactionManager;
	}

	/** An org created by sign-up, with its Admin's id and token. */
	public record SignedUp(long orgId, long adminId, String adminToken) {
	}

	public SignedUp signUp(String adminEmail, String orgName) throws Exception {
		String body = """
				{"email":"%s","password":"%s","displayName":"Admin","orgName":"%s"}""".formatted(adminEmail, PASSWORD, orgName);
		String response = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return new SignedUp(JsonPath.<Number>read(response, "$.org.id").longValue(),
				JsonPath.<Number>read(response, "$.user.id").longValue(), JsonPath.read(response, "$.token"));
	}

	/** A service with the same targets for every severity, owned by {@code ownerId}. Returns its id. */
	public long createService(String token, String name, long ownerId) throws Exception {
		String target = "{\"responseMinutes\": 15, \"resolutionMinutes\": 240}";
		String body = """
				{"name": "%s", "description": "Test service", "ownerId": %d, "criticality": "HIGH",
				 "targets": {"SEV1": %s, "SEV2": %s, "SEV3": %s, "SEV4": %s}}
				""".formatted(name, ownerId, target, target, target, target);
		return id(mvc.perform(post("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());
	}

	/** Declares an incident and returns the response body. */
	public String declareIncident(String token, long serviceId, String title) throws Exception {
		String body = "{\"title\":\"%s\",\"description\":\"Test incident\",\"serviceId\":%d,\"severity\":\"SEV2\"}"
				.formatted(title, serviceId);
		return mvc.perform(post("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	public static long id(String json) {
		return JsonPath.<Number>read(json, "$.id").longValue();
	}

	/** Deletes the org, everything it owns, and its members' accounts. */
	public void delete(long orgId) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			// Replica mode skips the append-only triggers and the foreign keys, so the order does not matter.
			jdbc.execute("SET LOCAL session_replication_role = 'replica'");
			jdbc.update("DELETE FROM service_sla_target WHERE service_id IN (SELECT id FROM service WHERE org_id = ?)", orgId);
			for (String table : ORG_TABLES) {
				jdbc.update("DELETE FROM " + table + " WHERE org_id = ?", orgId);
			}
			jdbc.update("DELETE FROM app_user WHERE id IN (SELECT user_id FROM membership WHERE org_id = ?)", orgId);
			jdbc.update("DELETE FROM membership WHERE org_id = ?", orgId);
			jdbc.update("DELETE FROM organization WHERE id = ?", orgId);
			jdbc.execute("SET LOCAL session_replication_role = 'origin'");
		});
	}

}
