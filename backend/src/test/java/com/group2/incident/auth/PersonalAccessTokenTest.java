package com.group2.incident.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.PersonalAccessToken;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.PersonalAccessTokenRepository;
import com.group2.incident.auth.security.PersonalAccessTokenFilter;
import com.group2.incident.auth.service.PersonalAccessTokenService;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.entity.Organization;
import com.group2.incident.org.repository.MembershipRepository;
import com.group2.incident.org.repository.OrganizationRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class PersonalAccessTokenTest {

	private static final String PASSWORD = "DevPassw0rd!";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PersonalAccessTokenRepository tokenRepository;

	@Autowired
	private PersonalAccessTokenService tokenService;

	@Autowired
	private MembershipRepository membershipRepository;

	@Autowired
	private AuditEntryRepository auditEntryRepository;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private Clock clock;

	private AppUser engineer;
	private AppUser otherEngineer;
	private String engineerJwt;
	private String otherEngineerJwt;

	@BeforeEach
	void setUp() throws Exception {
		Instant now = clock.instant();
		engineer = accounts.save(new AppUser("pat_engineer@test.local", "PAT Engineer", passwordEncoder.encode(PASSWORD), now), Role.ENGINEER);
		otherEngineer = accounts.save(new AppUser("pat_other@test.local", "Other Engineer", passwordEncoder.encode(PASSWORD), now), Role.ENGINEER);

		engineerJwt = login("pat_engineer@test.local");
		otherEngineerJwt = login("pat_other@test.local");
	}

	@AfterEach
	void resetClock() {
		ReflectionTestUtils.setField(tokenService, "clock", clock);
	}

	private String login(String email) throws Exception {
		String body = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD);
		String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.token");
	}

	@Test
	void createToken_returnsPlaintextOnce_storesHashOnly() throws Exception {
		// TC-FR-23.1a
		String response = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"Cursor\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("Cursor"))
				.andExpect(jsonPath("$.token").isString())
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		Long tokenId = JsonPath.<Number>read(response, "$.id").longValue();
		String rawToken = JsonPath.read(response, "$.token");

		assertThat(rawToken).startsWith("pat_");
		assertThat(rawToken).hasSize(4 + 64); // "pat_" + 64 hex chars = 68

		// Directly inspect database to confirm only SHA-256 hash is stored, never plaintext
		PersonalAccessToken stored = tokenRepository.findById(tokenId).orElseThrow();
		assertThat(stored.getTokenHash()).isNotEqualTo(rawToken);
		assertThat(stored.getTokenHash()).isEqualTo(PersonalAccessTokenService.hashToken(rawToken));
		assertThat(stored.getTokenHash()).hasSize(64);
	}

	@Test
	void createToken_duplicateName_returns409Conflict() throws Exception {
		mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"Cursor\"}"))
				.andExpect(status().isCreated());

		mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"cursor\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));
	}

	@Test
	void duplicateName_rejectedAtDatabaseLevel() {
		Instant now = clock.instant();
		String hash1 = "1111111111111111111111111111111111111111111111111111111111111111";
		String hash2 = "2222222222222222222222222222222222222222222222222222222222222222";

		tokenRepository.saveAndFlush(new PersonalAccessToken(engineer, "DbDuplicate", hash1, now, now.plus(Duration.ofDays(90))));

		assertThatThrownBy(() -> tokenRepository.saveAndFlush(
				new PersonalAccessToken(engineer, "dbduplicate", hash2, now, now.plus(Duration.ofDays(90)))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void differentUsers_canUseSameTokenName() {
		Instant now = clock.instant();
		String hash1 = "3333333333333333333333333333333333333333333333333333333333333333";
		String hash2 = "4444444444444444444444444444444444444444444444444444444444444444";

		tokenRepository.saveAndFlush(new PersonalAccessToken(engineer, "SharedName", hash1, now, now.plus(Duration.ofDays(90))));
		PersonalAccessToken otherToken = tokenRepository.saveAndFlush(
				new PersonalAccessToken(otherEngineer, "sharedname", hash2, now, now.plus(Duration.ofDays(90))));

		assertThat(otherToken.getId()).isNotNull();
	}

	@Test
	void createToken_reuseRevokedTokenName_succeeds() throws Exception {
		String createResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"ReusableToken\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		Long tokenId = JsonPath.<Number>read(createResp, "$.id").longValue();

		mvc.perform(delete("/api/me/tokens/" + tokenId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt))
				.andExpect(status().isNoContent());

		mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"reusabletoken\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("reusabletoken"));
	}

	@Test
	void duplicateName_afterRevocation_acceptedAtDatabaseLevel() {
		Instant now = clock.instant();
		String hash1 = "7777777777777777777777777777777777777777777777777777777777777777";
		String hash2 = "8888888888888888888888888888888888888888888888888888888888888888";

		PersonalAccessToken token1 = tokenRepository.saveAndFlush(
				new PersonalAccessToken(engineer, "RevokableDb", hash1, now, now.plus(Duration.ofDays(90))));
		token1.revoke(now);
		tokenRepository.saveAndFlush(token1);

		PersonalAccessToken token2 = tokenRepository.saveAndFlush(
				new PersonalAccessToken(engineer, "revokabledb", hash2, now, now.plus(Duration.ofDays(90))));

		assertThat(token2.getId()).isNotNull();
	}

	@Test
	void differentOrgs_canUseSameTokenName() {
		Instant now = clock.instant();
		Organization otherOrg = organizationRepository.saveAndFlush(
				new Organization("Other Org", "other-org-tokens-" + System.currentTimeMillis(), now));

		String hash1 = "5555555555555555555555555555555555555555555555555555555555555555";
		String hash2 = "6666666666666666666666666666666666666666666666666666666666666666";

		PersonalAccessToken token1 = tokenRepository.saveAndFlush(
				new PersonalAccessToken(engineer, "OrgSharedName", hash1, now, now.plus(Duration.ofDays(90))));

		jdbcTemplate.update("""
				INSERT INTO incident.personal_access_token (org_id, user_id, name, token_hash, created_at, expires_at)
				VALUES (?, ?, ?, ?, ?, ?)
				""", otherOrg.getId(), engineer.getId(), "orgsharedname", hash2, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plus(Duration.ofDays(90))));

		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM incident.personal_access_token WHERE user_id = ? AND lower(name) = lower(?)",
				Integer.class, engineer.getId(), "OrgSharedName");
		assertThat(count).isEqualTo(2);
		assertThat(token1.getId()).isNotNull();
	}

	@Test
	void createToken_blankName_returns400BadRequest() throws Exception {
		mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"  \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void listTokens_returnsOnlyOwnTokens() throws Exception {
		// TC-FR-23.1a
		mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"Token-1\"}"))
				.andExpect(status().isCreated());

		mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"Token-2\"}"))
				.andExpect(status().isCreated());

		// Other engineer creates a token
		mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + otherEngineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"Other-Token\"}"))
				.andExpect(status().isCreated());

		// List as engineer: returns exactly 2 tokens, neither token nor tokenHash exposed
		mvc.perform(get("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("Token-2"))
				.andExpect(jsonPath("$[1].name").value("Token-1"))
				.andExpect(jsonPath("$[0].token").doesNotExist())
				.andExpect(jsonPath("$[0].tokenHash").doesNotExist());
	}

	@Test
	void listTokens_identicalCreatedAt_ordersByIdDesc() throws Exception {
		// Verify deterministic secondary sort (IdDesc) when creation timestamps are identical
		Instant fixedNow = clock.instant();
		ReflectionTestUtils.setField(tokenService, "clock", Clock.fixed(fixedNow, ZoneOffset.UTC));

		mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"Tie-1\"}"))
				.andExpect(status().isCreated());

		mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"Tie-2\"}"))
				.andExpect(status().isCreated());

		mvc.perform(get("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Tie-2"))
				.andExpect(jsonPath("$[1].name").value("Tie-1"));
	}

	@Test
	void revokeToken_successAndIdempotent() throws Exception {
		// TC-FR-23.2c
		String createResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"ToRevoke\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		Long tokenId = JsonPath.<Number>read(createResp, "$.id").longValue();
		String rawToken = JsonPath.read(createResp, "$.token");

		// Token works initially
		mvc.perform(get("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
				.andExpect(status().isOk());

		// Revoke the token
		mvc.perform(delete("/api/me/tokens/" + tokenId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt))
				.andExpect(status().isNoContent());

		// Revoked token gets 401
		mvc.perform(get("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
				.andExpect(jsonPath("$.message").value("Personal access token has been revoked"));

		// Revoking again is an idempotent 204
		mvc.perform(delete("/api/me/tokens/" + tokenId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt))
				.andExpect(status().isNoContent());
	}

	@Test
	void revokeToken_otherUserToken_returns404() throws Exception {
		String createResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + otherEngineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"OtherUserToken\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		Long otherTokenId = JsonPath.<Number>read(createResp, "$.id").longValue();

		// Engineer tries to delete other engineer's token -> 404
		mvc.perform(delete("/api/me/tokens/" + otherTokenId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt))
				.andExpect(status().isNotFound());
	}

	@Test
	void patAuthentication_actsAsOwner_onGetIncidents() throws Exception {
		// TC-FR-23.2a
		String createResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"ApiAccess\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String rawToken = JsonPath.read(createResp, "$.token");

		mvc.perform(get("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
				.andExpect(status().isOk());
	}

	@Test
	void patAuthentication_disabledUser_returns401() throws Exception {
		// TC-FR-23.2c
		String createResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"DisabledTest\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String rawToken = JsonPath.read(createResp, "$.token");

		// Disable membership
		Membership membership = membershipRepository.findByUserIdAndOrgId(engineer.getId(), OrgContext.current()).orElseThrow();
		membership.disable();
		membershipRepository.saveAndFlush(membership);

		// Token now gets 401
		mvc.perform(get("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
				.andExpect(jsonPath("$.message").value("User account is disabled"));
	}

	@Test
	void patAuthentication_90DayExpiryBoundary() throws Exception {
		// TC-FR-23.2d
		Instant t0 = clock.instant();
		ReflectionTestUtils.setField(tokenService, "clock", Clock.fixed(t0, ZoneOffset.UTC));

		String createResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"ExpiryBoundary\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String rawToken = JsonPath.read(createResp, "$.token");
		Instant expiresAt = Instant.parse(JsonPath.read(createResp, "$.expiresAt"));

		// 1 second before 90-day expiry: valid (200 OK)
		Instant justBefore = expiresAt.minusSeconds(1);
		ReflectionTestUtils.setField(tokenService, "clock", Clock.fixed(justBefore, ZoneOffset.UTC));
		mvc.perform(get("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
				.andExpect(status().isOk());

		// Exactly at 90 days after creation: expired (401 Unauthorized)
		ReflectionTestUtils.setField(tokenService, "clock", Clock.fixed(expiresAt, ZoneOffset.UTC));
		mvc.perform(get("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
				.andExpect(jsonPath("$.message").value("Personal access token has expired"));
	}

	@Test
	void patAuthentication_rateLimit_61stRequestGets429() throws Exception {
		// TC-FR-23.2e
		String createResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"RateLimitToken\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String rawToken = JsonPath.read(createResp, "$.token");

		// 60 requests succeed
		for (int i = 0; i < 60; i++) {
			mvc.perform(get("/api/incidents")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
					.andExpect(status().isOk());
		}

		// 61st request gets 429 Too Many Requests with Retry-After header
		mvc.perform(get("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.status").value(429));
	}

	@Test
	void patAuthentication_cannotManageTokens() throws Exception {
		// TC-FR-23.3a: Self-management restriction
		String createResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"SelfManagementToken\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		Long tokenId = JsonPath.<Number>read(createResp, "$.id").longValue();
		String rawToken = JsonPath.read(createResp, "$.token");

		// PAT attempts to call GET /api/me/tokens -> 403 Forbidden
		mvc.perform(get("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
				.andExpect(status().isForbidden());

		// PAT attempts to call POST /api/me/tokens -> 403 Forbidden
		mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"RecursiveToken\"}"))
				.andExpect(status().isForbidden());

		// PAT attempts to call DELETE /api/me/tokens/{id} -> 403 Forbidden
		mvc.perform(delete("/api/me/tokens/" + tokenId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void auditAndLogs_doNotContainToken() throws Exception {
		// TC-FR-23.4a
		Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
		listAppender.start();
		rootLogger.addAppender(listAppender);

		long auditCountBefore = auditEntryRepository.count();

		try {
			// Create token
			String createResp = mvc.perform(post("/api/me/tokens")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"name\": \"AuditTestToken\"}"))
					.andExpect(status().isCreated())
					.andReturn().getResponse().getContentAsString();

			Long tokenId = JsonPath.<Number>read(createResp, "$.id").longValue();
			String rawToken = JsonPath.read(createResp, "$.token");
			String tokenHash = PersonalAccessTokenService.hashToken(rawToken);

			// Revoke token
			mvc.perform(delete("/api/me/tokens/" + tokenId)
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt))
					.andExpect(status().isNoContent());

			// Exactly two new audit entries
			List<AuditEntry> newAudits = auditEntryRepository.findAll().stream()
					.filter(a -> a.getId() > auditCountBefore)
					.toList();

			assertThat(newAudits).hasSize(2);
			assertThat(newAudits).extracting(AuditEntry::getAction)
					.containsExactlyInAnyOrder(AuditAction.TOKEN_CREATED, AuditAction.TOKEN_REVOKED);
			assertThat(newAudits).allMatch(a -> a.getItemType() == AuditItemType.TOKEN);
			assertThat(newAudits).allMatch(a -> a.getDetails().contains("AuditTestToken"));

			// Audit details must never contain the token or token hash
			for (AuditEntry audit : newAudits) {
				assertThat(audit.getDetails()).doesNotContain(rawToken);
				assertThat(audit.getDetails()).doesNotContain(tokenHash);
			}

			// Captured logs must never contain the token or token hash
			for (ILoggingEvent event : listAppender.list) {
				assertThat(event.getFormattedMessage()).doesNotContain(rawToken);
				assertThat(event.getFormattedMessage()).doesNotContain(tokenHash);
				if (event.getArgumentArray() != null) {
					for (Object arg : event.getArgumentArray()) {
						if (arg != null) {
							assertThat(arg.toString()).doesNotContain(rawToken);
							assertThat(arg.toString()).doesNotContain(tokenHash);
						}
					}
				}
			}
		}
		finally {
			rootLogger.detachAppender(listAppender);
		}
	}

	@Test
	void lastUsedAt_updatedOnUse() throws Exception {
		String createResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"UsageTest\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		Long tokenId = JsonPath.<Number>read(createResp, "$.id").longValue();
		String rawToken = JsonPath.read(createResp, "$.token");

		PersonalAccessToken beforeUse = tokenRepository.findById(tokenId).orElseThrow();
		assertThat(beforeUse.getLastUsedAt()).isNull();

		mvc.perform(get("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
				.andExpect(status().isOk());

		entityManager.clear();

		PersonalAccessToken afterUse = tokenRepository.findById(tokenId).orElseThrow();
		assertThat(afterUse.getLastUsedAt()).isNotNull();
	}

	@Test
	void patAuthentication_unknownToken_returns401() throws Exception {
		String unknownToken = "pat_" + "0".repeat(64);

		mvc.perform(get("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + unknownToken))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Invalid personal access token"));

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void patAuthentication_syntheticJwt_doesNotContainRawPat() throws Exception {
		String createResp = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerJwt)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"JwtPrivacyTest\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		Long tokenId = JsonPath.<Number>read(createResp, "$.id").longValue();
		String rawToken = JsonPath.read(createResp, "$.token");

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/incidents");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken);
		MockHttpServletResponse response = new MockHttpServletResponse();

		AtomicReference<Authentication> capturedAuth = new AtomicReference<>();
		FilterChain chain = (req, res) -> {
			capturedAuth.set(SecurityContextHolder.getContext().getAuthentication());
		};

		PersonalAccessTokenFilter filter = new PersonalAccessTokenFilter(tokenService);
		filter.doFilter(request, response, chain);

		assertThat(capturedAuth.get()).isInstanceOf(JwtAuthenticationToken.class);
		JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) capturedAuth.get();
		Jwt jwt = jwtAuth.getToken();

		assertThat(jwt.getTokenValue()).isEqualTo("pat-" + tokenId);
		assertThat(jwt.getTokenValue()).doesNotContain(rawToken);
		assertThat(jwtAuth.toString()).doesNotContain(rawToken);
		assertThat(jwt.getClaimAsString("sub")).isEqualTo(String.valueOf(engineer.getId()));
		assertThat(jwt.getClaimAsBoolean("pat")).isTrue();
		assertThat(jwt.<Number>getClaim("pat_id").longValue()).isEqualTo(tokenId);
		assertThat(jwtAuth.getAuthorities()).extracting("authority").containsExactly("ROLE_ENGINEER");
	}
}

