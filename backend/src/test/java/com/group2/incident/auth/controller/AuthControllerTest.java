package com.group2.incident.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.group2.incident.support.TestOrgExecutionListener;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AuthControllerTest {

	private static final String PASSWORD = "DevPassw0rd!";

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Autowired
	private Clock clock;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private EntityManager entityManager;

	@Test
	void loginReturnsTokenAndUser() throws Exception {
		AppUser admin = createUser("admin", Role.ADMIN);

		login("admin", PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				.andExpect(jsonPath("$.user.id").value(admin.getId()))
				.andExpect(jsonPath("$.user.email").value("admin@test.local"))
				.andExpect(jsonPath("$.user.displayName").value("Test admin"))
				.andExpect(jsonPath("$.user.role").value("ADMIN"))
				.andExpect(jsonPath("$.user.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.org.slug").value(TestOrgExecutionListener.TEST_ORG_SLUG));
	}

	@Test
	void tokenExpiresAfterOneHour() throws Exception {
		createUser("admin", Role.ADMIN);
		Instant before = clock.instant();

		String expiresAt = JsonPath.read(login("admin", PASSWORD).andReturn().getResponse().getContentAsString(), "$.expiresAt");

		assertThat(Instant.parse(expiresAt)).isBetween(before.plus(Duration.ofMinutes(59)), before.plus(Duration.ofMinutes(61)));
	}

	@Test
	void emailIsCaseInsensitive() throws Exception {
		createUser("admin", Role.ADMIN);

		login("ADMIN", PASSWORD).andExpect(status().isOk());
	}

	@Test
	void wrongPasswordReturns401() throws Exception {
		createUser("admin", Role.ADMIN);

		login("admin", "wrong-password")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid email or password"));
	}

	@Test
	void unknownUserGetsTheSame401AsAWrongPassword() throws Exception {
		login("nobody", PASSWORD)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid email or password"));
	}

	@Test
	void aDisabledMembershipGetsTheSame401AsAWrongPassword() throws Exception {
		createUser("engineer", Role.ENGINEER);
		disable("engineer");

		login("engineer", PASSWORD)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid email or password"));
	}

	@Test
	void blankFieldsReturn400NamingEachField() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\",\"password\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.email").value("Email is required"))
				.andExpect(jsonPath("$.errors.password").value("Password is required"));
	}

	@Test
	void meReturnsTheLoggedInUser() throws Exception {
		createUser("viewer", Role.VIEWER);
		String token = tokenFor("viewer");

		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.email").value("viewer@test.local"))
				.andExpect(jsonPath("$.user.role").value("VIEWER"))
				.andExpect(jsonPath("$.org.slug").value(TestOrgExecutionListener.TEST_ORG_SLUG));
	}

	@Test
	void meWithoutTokenReturns401() throws Exception {
		mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutMakesTheTokenStopWorking() throws Exception {
		createUser("engineer", Role.ENGINEER);
		String token = tokenFor("engineer");

		mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void tokenStopsWorkingWhenTheUserIsDisabled() throws Exception {
		createUser("engineer", Role.ENGINEER);
		String token = tokenFor("engineer");

		disable("engineer");

		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void expiredTokenReturns401() throws Exception {
		AppUser engineer = createUser("engineer", Role.ENGINEER);
		Instant issued = clock.instant().minus(Duration.ofHours(2));
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(engineer.getId().toString())
				.issuedAt(issued)
				.expiresAt(issued.plus(Duration.ofHours(1)))
				.claim("tv", engineer.getTokenVersion())
				.claim("org", OrgContext.current())
				.build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();

		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void adminOnlyPathsRefuseOtherRoles() throws Exception {
		createUser("engineer", Role.ENGINEER);
		String token = tokenFor("engineer");

		mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden());
	}

	@Test
	void viewerCannotCreateIncidents() throws Exception {
		createUser("viewer", Role.VIEWER);
		String token = tokenFor("viewer");

		mvc.perform(post("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden());
	}

	@Test
	void healthAndApiDocsNeedNoToken() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
	}

	@Test
	void theTokenCarriesTheOrgAndTheRole() throws Exception {
		createUser("ana", Role.ENGINEER);

		Jwt jwt = jwtDecoder.decode(tokenFor("ana"));

		assertThat(((Number) jwt.getClaim("org")).longValue()).isEqualTo(OrgContext.current());
		assertThat((String) jwt.getClaim("role")).isEqualTo("ENGINEER");
	}

	@Test
	void aTokenWithoutAnOrgIsRefused() throws Exception {
		AppUser engineer = createUser("engineer", Role.ENGINEER);

		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + signed(engineer, null)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void aTokenForAnOrgThePersonIsNotInIsRefused() throws Exception {
		AppUser engineer = createUser("engineer", Role.ENGINEER);
		Long otherOrg = jdbc.queryForObject(
				"INSERT INTO organization (name, slug, created_at) VALUES ('Other', 'other-auth', now()) RETURNING id", Long.class);

		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + signed(engineer, otherOrg)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void aRoleChangeAppliesToTheNextRequest() throws Exception {
		AppUser engineer = createUser("engineer", Role.ENGINEER);
		String token = signed(engineer, OrgContext.current());
		accounts.membership(engineer).changeRole(Role.VIEWER);
		entityManager.flush();
		entityManager.clear();

		// The token still says ENGINEER; the membership says VIEWER, and the membership wins.
		mvc.perform(post("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden());
	}

	// A valid token for engineer as of now, with the role ENGINEER and the given org (none if null).
	private String signed(AppUser user, Long org) {
		Instant now = clock.instant();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.subject(user.getId().toString())
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofHours(1)))
				.claim("tv", user.getTokenVersion())
				.claim("role", "ENGINEER");
		if (org != null) {
			claims.claim("org", org);
		}
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
	}

	@Test
	void aReEnabledMemberCanSignInAgain() throws Exception {
		AppUser engineer = createUser("engineer", Role.ENGINEER);
		disable("engineer");
		login("engineer", PASSWORD).andExpect(status().isUnauthorized());

		accounts.membership(engineer).enable();
		entityManager.flush();

		login("engineer", PASSWORD).andExpect(status().isOk());
	}

	@Test
	void anAccountWithoutAPasswordCannotSignInWithOne() throws Exception {
		// Google-only accounts (part 2 of D48) have no password; no password may open them.
		accounts.save(new AppUser("google@test.local", "Google User", null, clock.instant()), Role.ENGINEER);

		login("google", PASSWORD)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid email or password"));
	}

	private AppUser createUser(String username, Role role) {
		return accounts.save(new AppUser(username + "@test.local", "Test " + username, passwordEncoder.encode(PASSWORD), clock.instant()), role);
	}

	// Changes the row behind JPA's back, so the next lookup has to read it fresh.
	private void disable(String username) {
		accounts.membership(users.findByEmail(username + "@test.local").orElseThrow()).disable();
		entityManager.flush();
		entityManager.clear();
	}

	private ResultActions login(String username, String password) throws Exception {
		String body = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(username + "@test.local", password);
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String tokenFor(String username) throws Exception {
		return JsonPath.read(login(username, PASSWORD).andReturn().getResponse().getContentAsString(), "$.token");
	}

}
