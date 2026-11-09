package com.group2.incident.auth.controller;

import com.group2.incident.auth.dto.CreatePersonalAccessTokenRequest;
import com.group2.incident.auth.dto.CreatePersonalAccessTokenResponse;
import com.group2.incident.auth.dto.PersonalAccessTokenResponse;
import com.group2.incident.auth.service.PersonalAccessTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/tokens")
@RequiredArgsConstructor
@Tag(name = "Personal Access Tokens", description = "Personal access token management for the current user")
public class PersonalAccessTokenController {

	private final PersonalAccessTokenService tokenService;

	@GetMapping
	@Operation(summary = "List personal access tokens", description = "Lists personal access tokens created by the current user.")
	public List<PersonalAccessTokenResponse> listTokens(@AuthenticationPrincipal Jwt jwt) {
		requireNormalLogin(jwt);
		return tokenService.listTokens(userId(jwt));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a personal access token", description = "Returns the plaintext token once; only its hash is stored.")
	public CreatePersonalAccessTokenResponse createToken(
			@Valid @RequestBody CreatePersonalAccessTokenRequest request,
			@AuthenticationPrincipal Jwt jwt
	) {
		requireNormalLogin(jwt);
		return tokenService.createToken(request, userId(jwt));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Revoke a personal access token", description = "Revokes a personal access token created by the current user. Idempotent.")
	public void revokeToken(
			@PathVariable Long id,
			@AuthenticationPrincipal Jwt jwt
	) {
		requireNormalLogin(jwt);
		tokenService.revokeToken(id, userId(jwt));
	}

	private static void requireNormalLogin(Jwt jwt) {
		if (jwt != null && Boolean.TRUE.equals(jwt.getClaim("pat"))) {
			throw new AccessDeniedException("Personal access tokens cannot manage tokens");
		}
	}

	private static Long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}
}

