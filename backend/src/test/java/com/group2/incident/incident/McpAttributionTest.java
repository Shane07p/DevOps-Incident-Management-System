package com.group2.incident.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.auth.dto.CreatePersonalAccessTokenResponse;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.auth.service.PersonalAccessTokenService;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.entity.SlaTarget;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	private PersonalAccessTokenService tokenService;

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

	@BeforeEach
	void setUp() throws Exception {
		Instant now = clock.instant();

		engineer = users.findByEmail("mcp-engineer@test.local")
				.orElseGet(() -> accounts.save(new AppUser("mcp-engineer@test.local", "MCP Engineer", passwordEncoder.encode(PASSWORD), now), Role.ENGINEER));

		service = services.findByNameIgnoreCase("MCPService")
				.orElseGet(() -> {
					ServiceEntity s = new ServiceEntity("MCPService", "MCP Service Description", engineer, Criticality.TIER_1);
					s.setTargets(Map.of(
							Severity.SEV1, new SlaTarget(15, 60),
							Severity.SEV2, new SlaTarget(30, 120),
							Severity.SEV3, new SlaTarget(60, 240),
							Severity.SEV4, new SlaTarget(120, 480)
					));
					return services.save(s);
				});

		// Create PAT token
		OrgContext.set(1L);
		CreatePersonalAccessTokenResponse patResp = tokenService.createToken(engineer.getId(), "MCP Token", Optional.empty());
		patToken = patResp.token();

		// Create regular JWT login token
		String loginBody = "{\"email\":\"mcp-engineer@test.local\",\"password\":\"%s\"}".formatted(PASSWORD);
		String loginResp = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		jwtToken = JsonPath.read(loginResp, "$.token");
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
				120,
				null,
				null
		);
		incident.assignNumber(System.currentTimeMillis() % 1000000);
		return incidents.save(incident);
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
		assertThat(ackEntry.getDetails()).isEqualTo("Acknowledged by MCP Engineer via MCP");

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
		assertThat(ackEntry.getDetails()).isEqualTo("Acknowledged by MCP Engineer");
	}
}

