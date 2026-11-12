package com.group2.incident.heartbeat.controller;

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

/** Heartbeat settings and ping through the API. Who may call what is in SecurityMatrixTest; other orgs in OrgIsolationTest. */
@IntegrationTest
class HeartbeatControllerTest {

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
		admin = accounts.create("heartbeat_admin@test.local", Role.ADMIN);
		adminToken = accounts.token("heartbeat_admin@test.local");
		serviceId = orgs.createService(adminToken, "Payments", admin.getId());
	}

	@Test
	@DisplayName("Creating a heartbeat returns it, computes its ping URL, and audits it")
	void createReturnsTheHeartbeatAndAuditsIt() throws Exception {
		long id = TestOrgs.id(send(post("/api/heartbeats"), body("Nightly Backup", serviceId, 60, 10))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Nightly Backup"))
				.andExpect(jsonPath("$.serviceName").value("Payments"))
				.andExpect(jsonPath("$.intervalSeconds").value(60))
				.andExpect(jsonPath("$.graceSeconds").value(10))
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andExpect(jsonPath("$.pingUrl").isNotEmpty())
				.andExpect(jsonPath("$.nextDueAt").isNotEmpty())
				.andExpect(jsonPath("$.lateAfter").isNotEmpty())
				.andReturn().getResponse().getContentAsString());

		List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(AuditItemType.HEARTBEAT, id);
		assertThat(entries).singleElement().satisfies(entry -> {
			assertThat(entry.getAction()).isEqualTo(AuditAction.HEARTBEAT_CREATED);
			assertThat(entry.getActor().getId()).isEqualTo(admin.getId());
			assertThat(entry.getDetails()).contains("Nightly Backup on Payments", "ping every 60 s (grace 10 s)");
		});

		mvc.perform(get("/api/heartbeats").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(id));

		mvc.perform(get("/api/heartbeats/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id));
	}

	@Test
	@DisplayName("A change is audited with before and after; saving the same settings again writes nothing")
	void updateIsAuditedOnlyWhenSomethingChanges() throws Exception {
		long id = TestOrgs.id(send(post("/api/heartbeats"), body("Nightly Backup", serviceId, 60, 10))
				.andReturn().getResponse().getContentAsString());

		send(put("/api/heartbeats/{id}", id), body("Nightly Backup", serviceId, 120, 20))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.intervalSeconds").value(120))
				.andExpect(jsonPath("$.graceSeconds").value(20));

		send(put("/api/heartbeats/{id}", id), body("Nightly Backup", serviceId, 120, 20))
				.andExpect(status().isOk());

		List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(AuditItemType.HEARTBEAT, id);
		assertThat(entries).extracting(AuditEntry::getAction)
				.containsExactlyInAnyOrder(AuditAction.HEARTBEAT_CREATED, AuditAction.HEARTBEAT_UPDATED);
		assertThat(entries).filteredOn(entry -> entry.getAction() == AuditAction.HEARTBEAT_UPDATED).singleElement()
				.satisfies(entry -> assertThat(entry.getDetails()).contains("Was: ", "ping every 60 s (grace 10 s)", "Now: ", "ping every 120 s (grace 20 s)"));
	}

	@Test
	@DisplayName("Interval boundary: 29 s is refused, 30 s accepted")
	void intervalBoundary() throws Exception {
		send(post("/api/heartbeats"), body("Backup", serviceId, 29, 10))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.intervalSeconds").isNotEmpty());

		send(post("/api/heartbeats"), body("Backup", serviceId, 30, 10))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("Grace period boundary: -1 s is refused, 0 s accepted")
	void graceBoundary() throws Exception {
		send(post("/api/heartbeats"), body("Backup Negative Grace", serviceId, 60, -1))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.graceSeconds").isNotEmpty());

		send(post("/api/heartbeats"), body("Backup Zero Grace", serviceId, 60, 0))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("Public ping endpoint accepts GET and POST without login; invalid token returns 404")
	void publicPingEndpoint() throws Exception {
		String response = send(post("/api/heartbeats"), body("Cron Job", serviceId, 60, 10))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String pingUrl = com.jayway.jsonpath.JsonPath.read(response, "$.pingUrl");

		// POST ping without login
		mvc.perform(post(pingUrl))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"))
				.andExpect(jsonPath("$.name").value("Cron Job"));

		// GET ping without login
		mvc.perform(get(pingUrl))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"))
				.andExpect(jsonPath("$.name").value("Cron Job"));

		// Invalid token returns 404
		mvc.perform(post("/api/public/heartbeats/ping/nonexistent_token"))
				.andExpect(status().isNotFound());
	}

	private ResultActions send(MockHttpServletRequestBuilder builder, String json) throws Exception {
		return mvc.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String body(String name, long serviceId, int interval, int grace) {
		return """
				{"name": "%s", "serviceId": %d, "intervalSeconds": %d, "graceSeconds": %d, "enabled": true}
				""".formatted(name, serviceId, interval, grace);
	}
}
