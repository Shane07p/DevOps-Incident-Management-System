package com.group2.incident.auth.controller;

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
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class UserAdminControllerTest {

	private static final String DEFAULT_PASSWORD = "DevPassw0rd!";

	@Autowired
	private MockMvc mvc;

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
		admin = createUser("admin", Role.ADMIN);
		adminToken = tokenFor("admin", DEFAULT_PASSWORD);
	}

	@Test
	void passwordBelowEightCharactersReturns400() throws Exception {
		// TC-US-01a: Boundary value: Password of 7 characters -> 400 naming password
		String body = createUserBody("user7", "User 7", "1234567", "ENGINEER");

		mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").value("Password must be between 8 and 72 characters"));
	}

	@Test
	void passwordAtEightCharactersReturns201() throws Exception {
		// TC-US-01b: Boundary value: Password of 8 characters -> 201
		String body = createUserBody("user8", "User 8", "12345678", "ENGINEER");

		mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value("user8"))
				.andExpect(jsonPath("$.role").value("ENGINEER"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void passwordAtSeventyTwoCharactersReturns201() throws Exception {
		// TC-US-01c: Boundary value: Password of 72 characters -> 201
		String pass72 = "a".repeat(72);
		String body = createUserBody("user72", "User 72", pass72, "VIEWER");

		mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value("user72"))
				.andExpect(jsonPath("$.role").value("VIEWER"));
	}

	@Test
	void passwordAboveSeventyTwoCharactersReturns400() throws Exception {
		// TC-US-01d: Boundary value: Password of 73 characters -> 400
		String pass73 = "a".repeat(73);
		String body = createUserBody("user73", "User 73", pass73, "VIEWER");

		mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").value("Password must be between 8 and 72 characters"));
	}

	@Test
	void duplicateUsernameReturns409RegardlessOfCase() throws Exception {
		// TC-US-01e: Equivalence (invalid): Username that differs from an existing one only in case -> 409
		createUser("asha", Role.ENGINEER);

		String body = createUserBody("ASHA", "Asha Upper", "ValidPass123!", "ENGINEER");

		mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value("Username 'ASHA' is already taken"));
	}

	@Test
	void validRolesReturn201UnknownRoleReturns400() throws Exception {
		// TC-US-01f: Equivalence: Role ADMIN, ENGINEER, VIEWER; then an unknown role
		for (Role role : Role.values()) {
			String username = "user_" + role.name().toLowerCase();
			String body = createUserBody(username, "User " + role.name(), "ValidPass123!", role.name());

			mvc.perform(post("/api/users")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
							.contentType(MediaType.APPLICATION_JSON)
							.content(body))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.role").value(role.name()));
		}

		String unknownBody = createUserBody("unknown_role_user", "Unknown", "ValidPass123!", "SUPERUSER");
		mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(unknownBody))
				.andExpect(status().isBadRequest());
	}

	@Test
	void roleChangeIncrementsTokenVersionAndInvalidatesOldToken() throws Exception {
		// TC-US-01g: State transition: Change a user's role, then reuse their old token -> 401; new login carries new role
		createUser("member", Role.VIEWER);
		String oldToken = tokenFor("member", DEFAULT_PASSWORD);

		// Old token works initially
		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("VIEWER"));

		AppUser member = users.findByUsername("member").orElseThrow();

		// Admin changes member's role to ADMIN
		mvc.perform(put("/api/users/" + member.getId() + "/role")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\":\"ADMIN\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"));

		// Old token is invalidated immediately
		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
				.andExpect(status().isUnauthorized());

		// New login works and carries new role
		String newToken = tokenFor("member", DEFAULT_PASSWORD);
		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + newToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	void createUserAndRoleChangeWriteAuditEntries() throws Exception {
		// TC-US-01h: Negative: Creating a user and changing a role each writes one audit entry; password never appears
		String rawPassword = "TopSecretPassword123!";
		String body = createUserBody("audituser", "Audit User", rawPassword, "ENGINEER");

		String createResponse = mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		long createdId = ((Number) JsonPath.read(createResponse, "$.id")).longValue();

		List<AuditEntry> createEntries = auditEntries.findAll().stream()
				.filter(e -> e.getAction() == AuditAction.USER_CREATED && e.getItemId() == createdId)
				.toList();
		assertThat(createEntries).hasSize(1);
		AuditEntry createAudit = createEntries.getFirst();
		assertThat(createAudit.getActor().getId()).isEqualTo(admin.getId());
		assertThat(createAudit.getItemType()).isEqualTo(AuditItemType.USER);
		assertThat(createAudit.getDetails()).contains("username: audituser", "role: ENGINEER");
		assertThat(createAudit.getDetails()).doesNotContain(rawPassword);
		assertThat(createAudit.getDetails()).doesNotContain("$2a$");

		// Change role and verify second audit entry
		mvc.perform(put("/api/users/" + createdId + "/role")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\":\"ADMIN\"}"))
				.andExpect(status().isOk());

		List<AuditEntry> roleChangeEntries = auditEntries.findAll().stream()
				.filter(e -> e.getAction() == AuditAction.USER_ROLE_CHANGED && e.getItemId() == createdId)
				.toList();
		assertThat(roleChangeEntries).hasSize(1);
		AuditEntry roleAudit = roleChangeEntries.getFirst();
		assertThat(roleAudit.getActor().getId()).isEqualTo(admin.getId());
		assertThat(roleAudit.getItemType()).isEqualTo(AuditItemType.USER);
		assertThat(roleAudit.getDetails()).contains("role: ENGINEER → ADMIN");
		assertThat(roleAudit.getDetails()).doesNotContain(rawPassword);
		assertThat(roleAudit.getDetails()).doesNotContain("$2a$");
	}

	@Test
	void listUsersReturnsUsersWithoutPasswordHashes() throws Exception {
		createUser("engineer_one", Role.ENGINEER);
		createUser("viewer_one", Role.VIEWER);

		mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$[0].id").isNumber())
				.andExpect(jsonPath("$[0].username").isNotEmpty())
				.andExpect(jsonPath("$[0].displayName").isNotEmpty())
				.andExpect(jsonPath("$[0].role").isNotEmpty())
				.andExpect(jsonPath("$[*].passwordHash").doesNotExist())
				.andExpect(jsonPath("$[*].tokenVersion").doesNotExist());
	}

	@Test
	void nonAdminUsersCannotAccessUserEndpoints() throws Exception {
		createUser("engineer", Role.ENGINEER);
		createUser("viewer", Role.VIEWER);
		String engineerToken = tokenFor("engineer", DEFAULT_PASSWORD);
		String viewerToken = tokenFor("viewer", DEFAULT_PASSWORD);

		for (String token : List.of(engineerToken, viewerToken)) {
			mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
					.andExpect(status().isForbidden());

			mvc.perform(post("/api/users")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
							.contentType(MediaType.APPLICATION_JSON)
							.content(createUserBody("newbie", "Newbie", "ValidPass123!", "VIEWER")))
					.andExpect(status().isForbidden());

			mvc.perform(put("/api/users/" + admin.getId() + "/role")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"role\":\"ADMIN\"}"))
					.andExpect(status().isForbidden());
		}

		// Unauthenticated
		mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/users")
						.contentType(MediaType.APPLICATION_JSON)
						.content(createUserBody("newbie", "Newbie", "ValidPass123!", "VIEWER")))
				.andExpect(status().isUnauthorized());
		mvc.perform(put("/api/users/" + admin.getId() + "/role")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\":\"ADMIN\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void roleChangeForUnknownUserReturns404() throws Exception {
		mvc.perform(put("/api/users/999999/role")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\":\"ENGINEER\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));
	}

	private AppUser createUser(String username, Role role) {
		return users.saveAndFlush(new AppUser(username, "Test " + username, passwordEncoder.encode(DEFAULT_PASSWORD), role,
				clock.instant()));
	}

	private String tokenFor(String username, String password) throws Exception {
		String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
		String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.token");
	}

	private String createUserBody(String username, String displayName, String password, String role) {
		return """
				{
				  "username": "%s",
				  "displayName": "%s",
				  "password": "%s",
				  "role": "%s"
				}
				""".formatted(username, displayName, password, role);
	}

}
