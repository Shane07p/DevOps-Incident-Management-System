package com.group2.incident.auth.controller;

import com.group2.incident.auth.dto.LoginRequest;
import com.group2.incident.auth.dto.LoginResponse;
import com.group2.incident.auth.dto.UserResponse;
import com.group2.incident.auth.service.AuthService;
import com.group2.incident.common.ratelimit.ClientIpResolver;
import com.group2.incident.common.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Log in, see who you are, log out")
@RequiredArgsConstructor
class AuthController {

	private final AuthService auth;
	private final RateLimitService rateLimitService;
	private final ClientIpResolver clientIpResolver;

	@PostMapping("/login")
	@Operation(summary = "Log in and get a token for the Authorization header")
	@SecurityRequirements
	LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
		String clientIp = clientIpResolver.resolve(servletRequest);
		rateLimitService.checkLogin(clientIp, request.username());
		return auth.login(request);
	}

	@GetMapping("/me")
	@Operation(summary = "The logged-in user")
	UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return auth.me(userId(jwt));
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Log out: every token issued to this user so far stops working")
	void logout(@AuthenticationPrincipal Jwt jwt) {
		auth.logout(userId(jwt));
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

}
