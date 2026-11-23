package com.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.settle.support.IntegrationTest;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class IncidentManagementApplicationTests {

	@Autowired
	private Clock clock;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void migrationsRunInTheIncidentSchema() {
		Boolean exists = jdbc.queryForObject("select to_regclass('incident.flyway_schema_history') is not null", Boolean.class);
		assertThat(exists).isTrue();
	}

	@Test
	void clockIsUtc() {
		assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
	}

	@Test
	@WithMockUser
	void apiDocsDescribeBearerTokenLogin() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("Incident Management API"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
	}

}
