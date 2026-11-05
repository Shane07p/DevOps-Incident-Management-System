package com.group2.incident.org;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A member whose membership is disabled stays in the org but takes no part in it (D48): they cannot own
 * a service, be the fallback contact, take an on-call shift, or be an escalation responder.
 */
@IntegrationTest
class DisabledMemberTest {

	private static final String TARGET = "{\"responseMinutes\": 15, \"resolutionMinutes\": 240}";

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private Clock clock;

	private AppUser admin;

	private String adminToken;

	private AppUser disabled;

	private long serviceId;

	@BeforeEach
	void setUp() throws Exception {
		admin = accounts.create("disabled-admin@test.local", Role.ADMIN);
		adminToken = accounts.token("disabled-admin@test.local");
		disabled = accounts.create("disabled-member@test.local", Role.ENGINEER);
		serviceId = JsonPath.<Number>read(mvc.perform(post("/api/services")
								.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
								.contentType(MediaType.APPLICATION_JSON)
								.content(serviceBody("Payments", admin.getId())))
						.andExpect(status().isCreated())
						.andReturn().getResponse().getContentAsString(), "$.id").longValue();
		accounts.membership(disabled).disable();
		entityManager.flush();
	}

	@Test
	void aDisabledMemberCannotOwnAService() throws Exception {
		mvc.perform(post("/api/services")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(serviceBody("Orders", disabled.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.ownerId").value("Owner not found"));
	}

	@Test
	void aDisabledMemberCannotBeTheFallbackContact() throws Exception {
		putOnCall("{\"fallbackContactId\": %d, \"shifts\": []}".formatted(disabled.getId()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.fallbackContactId").value("Fallback contact must be an enabled user"));
	}

	@Test
	void aDisabledMemberCannotTakeAShift() throws Exception {
		Instant start = clock.instant().plus(Duration.ofHours(1));
		putOnCall("""
				{"fallbackContactId": %d, "shifts": [{"responderId": %d, "startTime": "%s", "endTime": "%s"}]}"""
				.formatted(admin.getId(), disabled.getId(), start, start.plus(Duration.ofHours(8))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.shifts").value("Responder must be an enabled user"));
	}

	@Test
	void aDisabledMemberCannotBeAnEscalationResponder() throws Exception {
		mvc.perform(put("/api/services/{id}/escalation-policy", serviceId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"steps\": [{\"stepNumber\": 1, \"responderId\": %d, \"waitMinutes\": 15}]}".formatted(disabled.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.steps").value("Responder user disabled-member is disabled"));
	}

	private ResultActions putOnCall(String body) throws Exception {
		return mvc.perform(put("/api/services/{id}/on-call", serviceId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private static String serviceBody(String name, long ownerId) {
		return """
				{"name": "%s", "description": "Test service", "ownerId": %d, "criticality": "HIGH",
				 "targets": {"SEV1": %s, "SEV2": %s, "SEV3": %s, "SEV4": %s}}
				""".formatted(name, ownerId, TARGET, TARGET, TARGET, TARGET);
	}

}
