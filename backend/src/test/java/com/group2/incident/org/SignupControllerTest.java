package com.group2.incident.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class SignupControllerTest {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactionManager;

	private static String body(String email, String org) {
		return """
				{"email":"%s","password":"DevPassw0rd!","displayName":"Ana","orgName":"%s"}""".formatted(email, org);
	}

	@Test
	void signUpCreatesTheOrgWithYouAsAdminAndSignsYouIn() throws Exception {
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("ana@acme.test", "Acme Corp")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.token").isString())
				.andExpect(jsonPath("$.user.role").value("ADMIN"))
				.andExpect(jsonPath("$.org.name").value("Acme Corp"))
				.andExpect(jsonPath("$.org.slug").value("acme-corp"));
	}

	@Test
	void aTakenEmailGives409() throws Exception {
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("dup@acme.test", "One")))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("DUP@acme.test", "Two")))
				.andExpect(status().isConflict());
	}

	@Test
	void twoOrgsWithOneNameGetDifferentSlugs() throws Exception {
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("a@same.test", "Same")))
				.andExpect(jsonPath("$.org.slug").value("same"));
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("b@same.test", "Same")))
				.andExpect(jsonPath("$.org.slug").value("same-2"));
	}

	@Test
	void aMissingOrgNameIsAFieldError() throws Exception {
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("x@acme.test", "")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.orgName").isString());
	}

	@Test
	void theSixthSignUpInAMinuteFromOneAddressGets429() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("rate" + i + "@acme.test", "Rate " + i)))
					.andExpect(status().isCreated());
		}
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body("rate5@acme.test", "Rate 5")))
				.andExpect(status().isTooManyRequests());
	}

	// Outside the test transaction, as in production: the audit entry is written in the new org's own session.
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void theNewOrgIsInItsOwnAuditLogAndItsTokenWorks() throws Exception {
		String response = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
						.content(body("committed@acme.test", "Committed Org")))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long orgId = JsonPath.<Number>read(response, "$.org.id").longValue();
		try {
			assertThat(jdbc.queryForObject(
					"SELECT count(*) FROM audit_entry WHERE org_id = ? AND action = 'ORG_CREATED' AND item_id = ?", Long.class, orgId, orgId))
					.isEqualTo(1);
			mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + JsonPath.read(response, "$.token")))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[*].email").value(Matchers.contains("committed@acme.test")));
		}
		finally {
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				// Replica mode skips the append-only trigger on audit_entry, and also foreign keys, so only this delete.
				jdbc.execute("SET LOCAL session_replication_role = 'replica'");
				jdbc.update("DELETE FROM audit_entry WHERE org_id = ?", orgId);
				jdbc.execute("SET LOCAL session_replication_role = 'origin'");
				// The membership goes with the org.
				jdbc.update("DELETE FROM organization WHERE id = ?", orgId);
				jdbc.update("DELETE FROM app_user WHERE email = ?", "committed@acme.test");
			});
		}
	}

}
