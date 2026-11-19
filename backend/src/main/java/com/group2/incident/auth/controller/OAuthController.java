package com.group2.incident.auth.controller;

import com.group2.incident.auth.dto.LoginResponse;
import com.group2.incident.auth.dto.OAuthCodeRequest;
import com.group2.incident.auth.dto.OAuthPendingSignupResponse;
import com.group2.incident.auth.dto.OAuthProvidersResponse;
import com.group2.incident.auth.dto.OAuthSignupRequest;
import com.group2.incident.auth.entity.OAuthProvider;
import com.group2.incident.auth.security.OAuthSettings;
import com.group2.incident.auth.service.OAuthSignInService;
import com.group2.incident.common.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The browser's side of Google and GitHub sign-in (D50). The sign-in itself starts at
 * /api/auth/oauth2/authorization/{google|github}, which Spring Security handles; these endpoints redeem its result.
 */
@RestController
@RequestMapping("/api/auth/oauth")
@Tag(name = "Auth", description = "Log in, see who you are, log out")
@RequiredArgsConstructor
class OAuthController {

	// One bucket per address for redeeming codes, separate from password logins and sign-ups (D42).
	private static final String CODE_BUCKET = "oauth-code";

	private final OAuthSignInService signIn;
	private final OAuthSettings settings;
	private final RateLimitService rateLimitService;

	@GetMapping("/providers")
	@Operation(summary = "The sign-in providers set up on this server, so the app shows only their buttons")
	@SecurityRequirements
	OAuthProvidersResponse providers() {
		return new OAuthProvidersResponse(settings.enabled().stream().map(OAuthProvider::registrationId).toList());
	}

	@PostMapping("/exchange")
	@Operation(summary = "Swap the one-time code from a Google or GitHub sign-in for a token")
	@SecurityRequirements
	LoginResponse exchange(@Valid @RequestBody OAuthCodeRequest request, HttpServletRequest servletRequest) {
		rateLimitService.checkLogin(servletRequest.getRemoteAddr(), CODE_BUCKET);
		return signIn.exchange(request.code());
	}

	@PostMapping("/signup/details")
	@Operation(summary = "The email and name behind a sign-up code from Google or GitHub, without using the code")
	@SecurityRequirements
	OAuthPendingSignupResponse signupDetails(@Valid @RequestBody OAuthCodeRequest request, HttpServletRequest servletRequest) {
		rateLimitService.checkLogin(servletRequest.getRemoteAddr(), CODE_BUCKET);
		return signIn.pendingSignup(request.code());
	}

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Finish a Google or GitHub sign-up: create the org, with you as its Admin")
	@SecurityRequirements
	LoginResponse signup(@Valid @RequestBody OAuthSignupRequest request, HttpServletRequest servletRequest) {
		rateLimitService.checkLogin(servletRequest.getRemoteAddr(), CODE_BUCKET);
		return signIn.completeSignup(request.code(), request.orgName(), request.displayName());
	}
}
