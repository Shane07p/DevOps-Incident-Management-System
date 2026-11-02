package com.group2.incident.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.support.IntegrationTest;
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
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AuthControllerTest {

	private static final String PASSWORD = "DevPassw0rd!";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtEncoder jwtEncoder;

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
				.andExpect(jsonPath("$.user.username").value("admin"))
				.andExpect(jsonPath("$.user.displayName").value("Test admin"))
				.andExpect(jsonPath("$.user.role").value("ADMIN"))
				.andExpect(jsonPath("$.user.passwordHash").doesNotExist());
	}

	@Test
	void tokenExpiresAfterOneHour() throws Exception {
		createUser("admin", Role.ADMIN);
		Instant before = clock.instant();

		String expiresAt = JsonPath.read(login("admin", PASSWORD).andReturn().getResponse().getContentAsString(), "$.expiresAt");

		assertThat(Instant.parse(expiresAt)).isBetween(before.plus(Duration.ofMinutes(59)), before.plus(Duration.ofMinutes(61)));
	}

	@Test
	void usernameIsCaseInsensitive() throws Exception {
		createUser("admin", Role.ADMIN);

		login("ADMIN", PASSWORD).andExpect(status().isOk());
	}

	@Test
	void wrongPasswordReturns401() throws Exception {
		createUser("admin", Role.ADMIN);

		login("admin", "wrong-password")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid username or password"));
	}

	@Test
	void unknownUserGetsTheSame401AsAWrongPassword() throws Exception {
		login("nobody", PASSWORD)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid username or password"));
	}

	@Test
	void disabledUserCannotLogIn() throws Exception {
		createUser("engineer", Role.ENGINEER);
		disable("engineer");

		login("engineer", PASSWORD).andExpect(status().isUnauthorized());
	}

	@Test
	void blankFieldsReturn400NamingEachField() throws Exception {
		login("", "")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.username").value("Username is required"))
				.andExpect(jsonPath("$.errors.password").value("Password is required"));
	}

	@Test
	void meReturnsTheLoggedInUser() throws Exception {
		createUser("viewer", Role.VIEWER);
		String token = tokenFor("viewer");

		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("viewer"))
				.andExpect(jsonPath("$.role").value("VIEWER"));
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

	private AppUser createUser(String username, Role role) {
		return users.saveAndFlush(new AppUser(username, username + "@test.local", "Test " + username, passwordEncoder.encode(PASSWORD), role,
				clock.instant()));
	}

	// Changes the row behind JPA's back, so the next lookup has to read it fresh.
	private void disable(String username) {
		entityManager.flush();
		jdbc.update("update app_user set enabled = false where username = ?", username);
		entityManager.clear();
	}

	private ResultActions login(String username, String password) throws Exception {
		String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String tokenFor(String username) throws Exception {
		return JsonPath.read(login(username, PASSWORD).andReturn().getResponse().getContentAsString(), "$.token");
	}

}
