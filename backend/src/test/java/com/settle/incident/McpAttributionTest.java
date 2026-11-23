package com.settle.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.settle.audit.entity.AuditAction;
import com.settle.audit.entity.AuditEntry;
import com.settle.audit.repository.AuditEntryRepository;
import com.settle.auth.entity.AppUser;
import com.settle.auth.entity.Role;
import com.settle.auth.repository.AppUserRepository;
import com.settle.catalog.entity.Criticality;
import com.settle.catalog.entity.ServiceEntity;
import com.settle.catalog.entity.Severity;
import com.settle.catalog.repository.ServiceRepository;
import com.settle.incident.entity.Incident;
import com.settle.incident.entity.IncidentStatus;
import com.settle.incident.entity.TimelineEntry;
import com.settle.incident.entity.TimelineEventType;
import com.settle.incident.repository.IncidentRepository;
import com.settle.incident.repository.TimelineEntryRepository;
import com.settle.support.IntegrationTest;
import com.settle.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class McpAttributionTest {

	private static final String PASSWORD = "DevPassw0rd!";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private TimelineEntryRepository timelineEntries;

	@Autowired
	private AuditEntryRepository auditEntries;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	private AppUser engineer;
	private ServiceEntity service;
	private String patToken;
	private String jwtToken;
	private String viewerPatToken;
	private long nextIncidentNumber = 950000L;

	@BeforeEach
	void setUp() throws Exception {
		Instant now = clock.instant();

		engineer = users.findByEmail("mcp-engineer@test.local")
				.orElseGet(() -> accounts.save(
						new AppUser("mcp-engineer@test.local", "MCP Engineer", passwordEncoder.encode(PASSWORD), now),
						Role.ENGINEER
				));

		users.findByEmail("mcp-viewer@test.local")
				.orElseGet(() -> accounts.save(
						new AppUser("mcp-viewer@test.local", "MCP Viewer", passwordEncoder.encode(PASSWORD), now),
						Role.VIEWER
				));

		service = services.findByNameIgnoreCase("MCPService")
				.orElseGet(() -> {
					ServiceEntity s = new ServiceEntity("MCPService", "MCP Service Description", engineer, Criticality.CRITICAL, now);
					s.putTarget(Severity.SEV1, 15, 60);
					s.putTarget(Severity.SEV2, 30, 120);
					s.putTarget(Severity.SEV3, 60, 240);
					s.putTarget(Severity.SEV4, 120, 480);
					return services.saveAndFlush(s);
				});

		// Create regular JWT login token
		String loginBody = "{\"email\":\"mcp-engineer@test.local\",\"password\":\"%s\"}".formatted(PASSWORD);
		String loginResp = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		jwtToken = JsonPath.read(loginResp, "$.token");

		// Create PAT token via authenticated endpoint
		String tokenResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"MCP Attribution Token\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		patToken = JsonPath.read(tokenResp, "$.token");

		// Create Viewer PAT token
		String viewerLoginBody = "{\"email\":\"mcp-viewer@test.local\",\"password\":\"%s\"}".formatted(PASSWORD);
		String viewerLoginResp = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(viewerLoginBody))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String viewerJwtToken = JsonPath.read(viewerLoginResp, "$.token");

		String viewerTokenResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerJwtToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Viewer PAT\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		viewerPatToken = JsonPath.read(viewerTokenResp, "$.token");
	}

	private Incident createTestIncident() {
		Instant now = clock.instant();
		Incident incident = new Incident(
				"MCP Test Incident",
				"Description",
				service,
				Severity.SEV2,
				IncidentStatus.OPEN,
				engineer,
				now,
				30,
				120
		);
		incident.assignNumber(nextIncidentNumber++);
		return incidents.saveAndFlush(incident);
	}

	@Test
	@DisplayName("Adding a comment via PAT records ' (via MCP)' on timeline and in audit log")
	void commentViaPatRecordsMcpAttribution() throws Exception {
		Incident incident = createTestIncident();

		mvc.perform(post("/api/incidents/" + incident.getId() + "/comments")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + patToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"comment\":\"Investigating database pool exhaustion.\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.details").value("Investigating database pool exhaustion. (via MCP)"));

		List<TimelineEntry> timeline = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		TimelineEntry commentEntry = timeline.stream()
				.filter(e -> e.getEventType() == TimelineEventType.COMMENT)
				.findFirst()
				.orElseThrow();
		assertThat(commentEntry.getDetails()).isEqualTo("Investigating database pool exhaustion. (via MCP)");

		List<AuditEntry> audits = auditEntries.findAll().stream()
				.filter(a -> a.getAction() == AuditAction.INCIDENT_COMMENT_ADDED && a.getItemId() == incident.getId())
				.toList();
		assertThat(audits).isNotEmpty();
		assertThat(audits.getLast().getDetails()).contains("via MCP");
	}

	@Test
	@DisplayName("Adding a comment via JWT does NOT record 'via MCP'")
	void commentViaJwtDoesNotRecordMcpAttribution() throws Exception {
		Incident incident = createTestIncident();

		mvc.perform(post("/api/incidents/" + incident.getId() + "/comments")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"comment\":\"Regular browser comment.\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.details").value("Regular browser comment."));

		List<TimelineEntry> timeline = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		TimelineEntry commentEntry = timeline.stream()
				.filter(e -> e.getEventType() == TimelineEventType.COMMENT)
				.findFirst()
				.orElseThrow();
		assertThat(commentEntry.getDetails()).isEqualTo("Regular browser comment.");

		List<AuditEntry> audits = auditEntries.findAll().stream()
				.filter(a -> a.getAction() == AuditAction.INCIDENT_COMMENT_ADDED && a.getItemId() == incident.getId())
				.toList();
		assertThat(audits).noneMatch(a -> a.getDetails().contains("via MCP"));
	}

	@Test
	@DisplayName("Acknowledging via PAT records 'via MCP' on timeline and in audit log")
	void acknowledgeViaPatRecordsMcpAttribution() throws Exception {
		Incident incident = createTestIncident();

		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + patToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":%d}".formatted(incident.getVersion())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

		List<TimelineEntry> timeline = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		TimelineEntry ackEntry = timeline.stream()
				.filter(e -> e.getEventType() == TimelineEventType.ACKNOWLEDGED)
				.findFirst()
				.orElseThrow();
		assertThat(ackEntry.getDetails()).isEqualTo("Acknowledged by " + engineer.getDisplayName() + " via MCP");

		List<AuditEntry> audits = auditEntries.findAll().stream()
				.filter(a -> a.getAction() == AuditAction.INCIDENT_ACKNOWLEDGED && a.getItemId() == incident.getId())
				.toList();
		assertThat(audits).isNotEmpty();
		assertThat(audits.getLast().getDetails()).contains("via MCP");
	}

	@Test
	@DisplayName("Acknowledging via JWT does NOT record 'via MCP'")
	void acknowledgeViaJwtDoesNotRecordMcpAttribution() throws Exception {
		Incident incident = createTestIncident();

		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":%d}".formatted(incident.getVersion())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

		List<TimelineEntry> timeline = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		TimelineEntry ackEntry = timeline.stream()
				.filter(e -> e.getEventType() == TimelineEventType.ACKNOWLEDGED)
				.findFirst()
				.orElseThrow();
		assertThat(ackEntry.getDetails()).isEqualTo("Acknowledged by " + engineer.getDisplayName());

		List<AuditEntry> audits = auditEntries.findAll().stream()
				.filter(a -> a.getAction() == AuditAction.INCIDENT_ACKNOWLEDGED && a.getItemId() == incident.getId())
				.toList();
		assertThat(audits).noneMatch(a -> a.getDetails().contains("via MCP"));
	}

	@Test
	@DisplayName("Viewer PAT is forbidden from adding comments or acknowledging incidents (403)")
	void viewerPatCannotCommentOrAcknowledge() throws Exception {
		Incident incident = createTestIncident();

		mvc.perform(post("/api/incidents/" + incident.getId() + "/comments")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerPatToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"comment\":\"Viewer attempting comment.\"}"))
				.andExpect(status().isForbidden());

		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerPatToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":%d}".formatted(incident.getVersion())))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Acknowledging with a stale expectedVersion returns 409 Conflict")
	void acknowledgeConflictOnStaleVersion() throws Exception {
		Incident incident = createTestIncident();

		mvc.perform(post("/api/incidents/" + incident.getId() + "/acknowledge")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + patToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":%d}".formatted(incident.getVersion() + 999)))
				.andExpect(status().isConflict());
	}
}
