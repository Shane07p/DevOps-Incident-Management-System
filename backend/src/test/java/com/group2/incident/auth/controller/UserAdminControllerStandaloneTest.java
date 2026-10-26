package com.group2.incident.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.dto.ChangeRoleRequest;
import com.group2.incident.auth.dto.CreateUserRequest;
import com.group2.incident.auth.dto.UserResponse;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.service.UserAdminService;
import com.group2.incident.common.error.ApiExceptionHandler;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class UserAdminControllerStandaloneTest {

	@Mock
	private UserAdminService userAdminService;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		UserAdminController controller = new UserAdminController(userAdminService);
		mvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new ApiExceptionHandler())
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.build();

		Jwt jwt = Jwt.withTokenValue("mock-token")
				.header("alg", "none")
				.claim("sub", "1")
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(3600))
				.build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void passwordBelowEightCharactersReturns400() throws Exception {
		// TC-US-01a: Boundary value: Password of 7 characters -> 400 naming password
		String body = """
				{
				  "username": "user7",
				  "displayName": "User 7",
				  "password": "1234567",
				  "role": "ENGINEER"
				}
				""";

		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errors.password").value("Password must be between 8 and 72 characters"));
	}

	@Test
	void passwordAtEightCharactersReturns201() throws Exception {
		// TC-US-01b: Boundary value: Password of 8 characters -> 201
		String body = """
				{
				  "username": "user8",
				  "displayName": "User 8",
				  "password": "12345678",
				  "role": "ENGINEER"
				}
				""";

		when(userAdminService.createUser(any(CreateUserRequest.class), eq(1L)))
				.thenReturn(new UserResponse(8L, "user8", "User 8", Role.ENGINEER));

		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(8))
				.andExpect(jsonPath("$.username").value("user8"))
				.andExpect(jsonPath("$.role").value("ENGINEER"));
	}

	@Test
	void passwordAtSeventyTwoCharactersReturns201() throws Exception {
		// TC-US-01c: Boundary value: Password of 72 characters -> 201
		String pass72 = "a".repeat(72);
		String body = """
				{
				  "username": "user72",
				  "displayName": "User 72",
				  "password": "%s",
				  "role": "VIEWER"
				}
				""".formatted(pass72);

		when(userAdminService.createUser(any(CreateUserRequest.class), eq(1L)))
				.thenReturn(new UserResponse(72L, "user72", "User 72", Role.VIEWER));

		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(72))
				.andExpect(jsonPath("$.username").value("user72"))
				.andExpect(jsonPath("$.role").value("VIEWER"));
	}

	@Test
	void passwordAboveSeventyTwoCharactersReturns400() throws Exception {
		// TC-US-01d: Boundary value: Password of 73 characters -> 400
		String pass73 = "a".repeat(73);
		String body = """
				{
				  "username": "user73",
				  "displayName": "User 73",
				  "password": "%s",
				  "role": "VIEWER"
				}
				""".formatted(pass73);

		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.password").value("Password must be between 8 and 72 characters"));
	}

	@Test
	void duplicateUsernameReturns409() throws Exception {
		// TC-US-01e: Equivalence (invalid): Duplicate username -> 409
		String body = """
				{
				  "username": "ASHA",
				  "displayName": "Asha",
				  "password": "ValidPass123!",
				  "role": "ENGINEER"
				}
				""";

		when(userAdminService.createUser(any(CreateUserRequest.class), eq(1L)))
				.thenThrow(new ConflictException("Username 'ASHA' is already taken"));

		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value("Username 'ASHA' is already taken"));
	}

	@Test
	void unknownRoleReturns400() throws Exception {
		// TC-US-01f: Unknown role -> 400
		String body = """
				{
				  "username": "badrole",
				  "displayName": "Bad Role",
				  "password": "ValidPass123!",
				  "role": "SUPERUSER"
				}
				""";

		mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
	}

	@Test
	void listUsersReturnsUsers() throws Exception {
		when(userAdminService.listUsers()).thenReturn(List.of(
				new UserResponse(1L, "admin", "Admin", Role.ADMIN),
				new UserResponse(2L, "engineer", "Engineer", Role.ENGINEER)
		));

		mvc.perform(get("/api/users"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$[0].username").value("admin"))
				.andExpect(jsonPath("$[1].username").value("engineer"))
				.andExpect(jsonPath("$[*].passwordHash").doesNotExist())
				.andExpect(jsonPath("$[*].tokenVersion").doesNotExist());
	}

	@Test
	void changeRoleReturnsUpdatedUser() throws Exception {
		when(userAdminService.changeRole(eq(5L), any(ChangeRoleRequest.class), eq(1L)))
				.thenReturn(new UserResponse(5L, "target", "Target", Role.ADMIN));

		mvc.perform(put("/api/users/5/role")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\":\"ADMIN\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(5))
				.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	void changeRoleForUnknownUserReturns404() throws Exception {
		when(userAdminService.changeRole(eq(999L), any(ChangeRoleRequest.class), eq(1L)))
				.thenThrow(new NotFoundException("User 999 not found"));

		mvc.perform(put("/api/users/999/role")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\":\"ADMIN\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("User 999 not found"));
	}
}
