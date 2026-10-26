package com.group2.incident.auth.controller;

import com.group2.incident.auth.dto.ChangeRoleRequest;
import com.group2.incident.auth.dto.CreateUserRequest;
import com.group2.incident.auth.dto.UserResponse;
import com.group2.incident.auth.service.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "User administration API (Admin only)")
@RequiredArgsConstructor
public class UserAdminController {

	private final UserAdminService userAdminService;

	@GetMapping
	@Operation(summary = "List all users without password hashes")
	public List<UserResponse> listUsers() {
		return userAdminService.listUsers();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a new user")
	public UserResponse createUser(
			@Valid @RequestBody CreateUserRequest request,
			@AuthenticationPrincipal Jwt jwt
	) {
		return userAdminService.createUser(request, userId(jwt));
	}

	@PutMapping("/{id}/role")
	@Operation(summary = "Change a user's role and invalidate their existing tokens")
	public UserResponse changeRole(
			@PathVariable long id,
			@Valid @RequestBody ChangeRoleRequest request,
			@AuthenticationPrincipal Jwt jwt
	) {
		return userAdminService.changeRole(id, request, userId(jwt));
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

}
