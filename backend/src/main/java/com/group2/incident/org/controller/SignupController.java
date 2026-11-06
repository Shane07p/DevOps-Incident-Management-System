package com.group2.incident.org.controller;

import com.group2.incident.auth.dto.LoginResponse;
import com.group2.incident.common.ratelimit.RateLimitService;
import com.group2.incident.org.dto.SignupRequest;
import com.group2.incident.org.service.SignupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Auth", description = "Log in, see who you are, log out")
@RequiredArgsConstructor
class SignupController {

	// One bucket per address for every sign-up, separate from login attempts (D42).
	private static final String SIGNUP_BUCKET = "sign-up";

	private final SignupService signupService;
	private final RateLimitService rateLimitService;

	@PostMapping("/api/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create an account and a new org, with you as its Admin")
	@SecurityRequirements
	LoginResponse signup(@Valid @RequestBody SignupRequest request, HttpServletRequest servletRequest) {
		rateLimitService.checkLogin(servletRequest.getRemoteAddr(), SIGNUP_BUCKET);
		return signupService.signup(request);
	}

}
