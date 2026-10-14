package com.group2.incident.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Real credentials against the seeded users, until JWT login replaces HTTP Basic. */
@IntegrationTest
class SecurityConfigTest {

	private static final String PASSWORD = "DevPassw0rd!";

	@Autowired
	private MockMvc mvc;

	@Test
	void noCredentialsIs401WithoutABrowserLoginPrompt() throws Exception {
		mvc.perform(get("/api/services"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist("WWW-Authenticate"));
		mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void wrongPasswordIs401() throws Exception {
		mvc.perform(get("/api/services").with(httpBasic("admin", "wrong"))).andExpect(status().isUnauthorized());
	}

	@Test
	void seededUsersSignInWithTheDevPassword() throws Exception {
		mvc.perform(get("/api/services").with(httpBasic("viewer", PASSWORD))).andExpect(status().isOk());
	}

	@Test
	void roleComesFromTheDatabase() throws Exception {
		// Admin gets past the role check and reaches validation (no targets); Engineer is stopped first.
		String body = """
				{"name": "Search", "ownerId": 1, "criticality": "LOW", "targets": {}}
				""";
		mvc.perform(post("/api/services").with(httpBasic("engineer", PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
		mvc.perform(post("/api/services").with(httpBasic("admin", PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
	}

	@Test
	void healthAndApiDocsAreOpen() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
	}

}
