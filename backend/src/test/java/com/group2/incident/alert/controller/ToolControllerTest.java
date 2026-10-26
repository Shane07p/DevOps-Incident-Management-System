package com.group2.incident.alert.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.repository.MonitoringToolRepository;
import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ToolControllerTest {

	private static final String DEFAULT_PASSWORD = "DevPassw0rd!";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MonitoringToolRepository tools;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private AuditEntryRepository auditEntries;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	private AppUser admin;
	private String adminToken;

	@BeforeEach
	void setUp() throws Exception {
		admin = createUser("tool_admin", Role.ADMIN);
		adminToken = tokenFor("tool_admin", DEFAULT_PASSWORD);
	}

	@Test
	void registerTool_showsTokenOnce_persistsOnlySha256Hash_andReadsNeverReturnToken() throws Exception {
		// TC-FR-03.1a: Register a tool: token shown once, later reads never return it, only hash stored
		String createResponse = mvc.perform(post("/api/tools")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Prometheus EU\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("Prometheus EU"))
				.andExpect(jsonPath("$.token").isString())
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		Long toolId = ((Number) JsonPath.read(createResponse, "$.id")).longValue();
		String rawToken = JsonPath.read(createResponse, "$.token");
		assertThat(rawToken).isNotBlank();
		assertThat(rawToken).startsWith("tt_");

		// Verify database stores only SHA-256 hash
		MonitoringTool storedTool = tools.findById(toolId).orElseThrow();
		assertThat(storedTool.getName()).isEqualTo("Prometheus EU");
		assertThat(storedTool.getTokenHash()).isNotBlank();
		assertThat(storedTool.getTokenHash()).isNotEqualTo(rawToken);

		// Verify SHA-256 computation matches stored hash
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] expectedHash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
		assertThat(storedTool.getTokenHash()).isEqualTo(HexFormat.of().formatHex(expectedHash));

		// Verify subsequent GET /api/tools never returns the token or token hash
		mvc.perform(get("/api/tools")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + toolId + ")].name").value("Prometheus EU"))
				.andExpect(jsonPath("$[?(@.id == " + toolId + ")].token").doesNotExist())
				.andExpect(jsonPath("$[?(@.id == " + toolId + ")].tokenHash").doesNotExist());
	}

	@Test
	void duplicateToolName_returns409Conflict() throws Exception {
		mvc.perform(post("/api/tools")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Datadog\"}"))
				.andExpect(status().isCreated());

		// Case-insensitive duplicate check
		mvc.perform(post("/api/tools")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"datadog\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value("Tool with name 'datadog' already exists"));
	}

	@Test
	void blankToolName_returns400BadRequest() throws Exception {
		mvc.perform(post("/api/tools")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.name").value("Tool name is required"));
	}

	@Test
	void revokeTool_setsRevokedAtAndReturns204() throws Exception {
		String response = mvc.perform(post("/api/tools")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Sentry\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		Long toolId = ((Number) JsonPath.read(response, "$.id")).longValue();

		mvc.perform(delete("/api/tools/" + toolId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isNoContent());

		MonitoringTool revoked = tools.findById(toolId).orElseThrow();
		assertThat(revoked.isRevoked()).isTrue();
		assertThat(revoked.getRevokedAt()).isNotNull();

		// Listing reflects revokedAt timestamp
		mvc.perform(get("/api/tools")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + toolId + ")].revokedAt").isNotEmpty());
	}

	@Test
	void revokeTool_unknownId_returns404() throws Exception {
		mvc.perform(delete("/api/tools/999999")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Tool 999999 not found"));
	}

	@Test
	void toolActions_recordAuditEntries() throws Exception {
		String response = mvc.perform(post("/api/tools")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Audit Tool\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		Long toolId = ((Number) JsonPath.read(response, "$.id")).longValue();

		List<AuditEntry> createdAudits = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(
				AuditItemType.TOOL, toolId);
		assertThat(createdAudits).hasSize(1);
		assertThat(createdAudits.get(0).getAction()).isEqualTo(AuditAction.TOOL_REGISTERED);
		assertThat(createdAudits.get(0).getActor().getId()).isEqualTo(admin.getId());
		assertThat(createdAudits.get(0).getDetails()).contains("name: Audit Tool");

		mvc.perform(delete("/api/tools/" + toolId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isNoContent());

		List<AuditEntry> allAudits = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(
				AuditItemType.TOOL, toolId);
		assertThat(allAudits).hasSize(2);
		assertThat(allAudits.get(0).getAction()).isEqualTo(AuditAction.TOOL_REVOKED);
		assertThat(allAudits.get(0).getActor().getId()).isEqualTo(admin.getId());
	}

	private AppUser createUser(String username, Role role) {
		return users.saveAndFlush(new AppUser(username, "Test " + username,
				passwordEncoder.encode(DEFAULT_PASSWORD), role, clock.instant()));
	}

	private String tokenFor(String username, String password) throws Exception {
		String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
		String response = mvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.token");
	}
}
