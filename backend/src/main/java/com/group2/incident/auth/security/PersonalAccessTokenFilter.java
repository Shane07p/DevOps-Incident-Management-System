package com.group2.incident.auth.security;

import com.group2.incident.auth.service.PersonalAccessTokenService;
import com.group2.incident.auth.service.PersonalAccessTokenService.ValidatedPat;
import com.group2.incident.common.error.ApiError;
import com.group2.incident.common.error.UnauthorizedException;
import com.group2.incident.common.ratelimit.RateLimitExceededException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authenticates requests carrying a personal access token (D38, FR-23).
 * Intercepts "Authorization: Bearer pat_...", hashes the token, validates expiry and user status,
 * enforces rate limits, and establishes a synthetic JwtAuthenticationToken for downstream filters.
 */
@Slf4j
@RequiredArgsConstructor
public class PersonalAccessTokenFilter extends OncePerRequestFilter {

	private static final String BEARER_PAT_PREFIX = "Bearer pat_";

	private final PersonalAccessTokenService tokenService;
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authHeader == null || !authHeader.startsWith(BEARER_PAT_PREFIX)) {
			filterChain.doFilter(request, response);
			return;
		}

		String rawToken = authHeader.substring(7).trim();
		try {
			ValidatedPat validated = tokenService.validateToken(rawToken);

			Jwt jwt = Jwt.withTokenValue(rawToken)
					.header("alg", "none")
					.subject(String.valueOf(validated.user().getId()))
					.issuedAt(validated.token().getCreatedAt())
					.expiresAt(validated.token().getExpiresAt())
					.claim(JwtUserConverter.ORG, validated.orgId())
					.claim(JwtUserConverter.ROLE, validated.role().name())
					.claim("pat", true)
					.claim("pat_id", validated.token().getId())
					.build();

			JwtAuthenticationToken authentication = new JwtAuthenticationToken(
					jwt,
					List.of(new SimpleGrantedAuthority("ROLE_" + validated.role().name()))
			);
			SecurityContextHolder.getContext().setAuthentication(authentication);

			filterChain.doFilter(request, response);
		}
		catch (UnauthorizedException ex) {
			SecurityContextHolder.clearContext();
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			jsonMapper.writeValue(response.getWriter(), new ApiError(HttpStatus.UNAUTHORIZED.value(), ex.getMessage(), Map.of()));
		}
		catch (RateLimitExceededException ex) {
			SecurityContextHolder.clearContext();
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			response.setHeader("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			jsonMapper.writeValue(response.getWriter(), new ApiError(HttpStatus.TOO_MANY_REQUESTS.value(), ex.getMessage(), Map.of()));
		}
	}
}
