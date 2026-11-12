package com.group2.incident.monitor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.group2.incident.support.TestOrgs;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Monitor settings through the API. Who may call what is in SecurityMatrixTest; other orgs in OrgIsolationTest. */
@IntegrationTest
class MonitorControllerTest {

	private static final String PUBLIC_URL = "https://203.0.113.10/health";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private AuditEntryRepository auditEntries;

	private AppUser admin;

	private String adminToken;

	private long serviceId;

	@BeforeEach
	void setUp() throws Exception {
		admin = accounts.create("monitor_admin@test.local", Role.ADMIN);
		adminToken = accounts.token("monitor_admin@test.local");
		serviceId = orgs.createService(adminToken, "Checkout", admin.getId());
	}

	@Test
	@DisplayName("Creating a monitor returns it, schedules its first check now, and audits it")
	void createReturnsTheMonitorAndAuditsIt() throws Exception {
		long id = TestOrgs.id(send(post("/api/monitors"), body("Checkout health", PUBLIC_URL, 60, serviceId))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Checkout health"))
				.andExpect(jsonPath("$.serviceName").value("Checkout"))
				.andExpect(jsonPath("$.consecutiveFailures").value(0))
				.andExpect(jsonPath("$.nextCheckAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString());

		List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(AuditItemType.MONITOR, id);
		assertThat(entries).singleElement().satisfies(entry -> {
			assertThat(entry.getAction()).isEqualTo(AuditAction.MONITOR_CREATED);
			assertThat(entry.getActor().getId()).isEqualTo(admin.getId());
			assertThat(entry.getDetails()).contains(PUBLIC_URL, "every 60 s", "alerts after 3 failures in a row");
		});

		mvc.perform(get("/api/monitors").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(id));
	}

	@Test
	@DisplayName("A change is audited with before and after; saving the same settings again writes nothing")
	void updateIsAuditedOnlyWhenSomethingChanges() throws Exception {
		long id = TestOrgs.id(send(post("/api/monitors"), body("Checkout health", PUBLIC_URL, 60, serviceId))
				.andReturn().getResponse().getContentAsString());

		send(put("/api/monitors/{id}", id), body("Checkout health", PUBLIC_URL, 120, serviceId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.intervalSeconds").value(120));
		send(put("/api/monitors/{id}", id), body("Checkout health", PUBLIC_URL, 120, serviceId))
				.andExpect(status().isOk());

		List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(AuditItemType.MONITOR, id);
		assertThat(entries).extracting(AuditEntry::getAction)
				.containsExactlyInAnyOrder(AuditAction.MONITOR_CREATED, AuditAction.MONITOR_UPDATED);
		assertThat(entries).filteredOn(entry -> entry.getAction() == AuditAction.MONITOR_UPDATED).singleElement()
				.satisfies(entry -> assertThat(entry.getDetails()).contains("Was: ", "every 60 s", "Now: ", "every 120 s"));
	}

	@Test
	@DisplayName("TC-FR-22.1e: An interval of 29 seconds is refused and 30 is accepted")
	void intervalBoundary() throws Exception {
		send(post("/api/monitors"), body("Too often", PUBLIC_URL, 29, serviceId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.intervalSeconds").value("Interval must be at least 30 seconds"));
		send(post("/api/monitors"), body("Often enough", PUBLIC_URL, 30, serviceId))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("TC-FR-22.1f: A URL on our own network is refused with a field error")
	void internalUrlIsRefused() throws Exception {
		send(post("/api/monitors"), body("Metadata", "http://169.254.169.254/latest/meta-data/", 60, serviceId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.url").value("The URL must not point to a private or local network address"));
		send(post("/api/monitors"), body("FTP", "ftp://203.0.113.10/file", 60, serviceId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.url").exists());
	}

	@Test
	@DisplayName("An unknown service is a field error, and an unknown monitor is 404")
	void unknownIds() throws Exception {
		send(post("/api/monitors"), body("Orphan", PUBLIC_URL, 60, 999_999_999L))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.serviceId").value("Service not found"));
		send(put("/api/monitors/{id}", 999_999_999L), body("Missing", PUBLIC_URL, 60, serviceId))
				.andExpect(status().isNotFound());
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String body(String name, String url, int intervalSeconds, long serviceId) {
		return """
				{"name": "%s", "url": "%s", "expectedStatus": 200, "intervalSeconds": %d, "serviceId": %d,
				 "failureThreshold": 3, "enabled": true}""".formatted(name, url, intervalSeconds, serviceId);
	}
}
