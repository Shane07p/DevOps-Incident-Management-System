package com.group2.incident.telemetry;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The telemetry endpoints through the full app. Grafana is not set up in tests, so a valid request gets the 503
 * that says so, and the checks that run before Grafana is called (service, parameters) are seen on their own.
 */
@IntegrationTest
class TelemetryControllerTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private Clock clock;

	private String token;

	private long serviceId;

	@BeforeEach
	void setUp() throws Exception {
		AppUser viewer = accounts.create("telemetry-viewer@test.local", Role.VIEWER);
		token = accounts.token("telemetry-viewer@test.local");
		serviceId = services.saveAndFlush(new ServiceEntity("Checkout", "Test", viewer, Criticality.HIGH, clock.instant())).getId();
	}

	@Test
	void anyRoleReadsTelemetryAndTheAppSaysWhenGrafanaIsNotSetUp() throws Exception {
		mvc.perform(get("/api/telemetry/logs").param("serviceId", String.valueOf(serviceId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value("Logs are not set up on this server"));
		mvc.perform(get("/api/telemetry/metrics").param("serviceId", String.valueOf(serviceId)).param("metric", "errorRate")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value("Metrics are not set up on this server"));
	}

	@Test
	void aServiceIsRequiredAndAnUnknownOneIsNotFound() throws Exception {
		mvc.perform(get("/api/telemetry/logs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/telemetry/metrics").param("metric", "errorRate").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/telemetry/logs").param("serviceId", "999999999").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isNotFound());
	}

	@Test
	void badParametersAre400BeforeGrafanaIsAsked() throws Exception {
		mvc.perform(get("/api/telemetry/logs").param("serviceId", String.valueOf(serviceId)).param("from", "yesterday")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.from").isNotEmpty());
		mvc.perform(get("/api/telemetry/logs").param("serviceId", String.valueOf(serviceId))
						.param("from", "2026-11-09T00:00:00Z").param("to", "2026-11-10T00:00:01Z")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.from").value("The time range can be at most 24 hours"));
		mvc.perform(get("/api/telemetry/metrics").param("serviceId", String.valueOf(serviceId)).param("metric", "errorRate")
						.param("step", "1s").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.step").isNotEmpty());
	}
}
