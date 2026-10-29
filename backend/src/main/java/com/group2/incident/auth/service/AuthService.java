package com.group2.incident.auth.service;

import com.group2.incident.auth.dto.LoginRequest;
import com.group2.incident.auth.dto.LoginResponse;
import com.group2.incident.auth.dto.UserResponse;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.auth.security.JwtUserConverter;
import com.group2.incident.common.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

	private static final Duration TOKEN_LIFETIME = Duration.ofHours(1);

	private final AuthenticationManager authenticationManager;

	private final AppUserRepository users;

	private final JwtEncoder jwtEncoder;

	private final Clock clock;

	/** Checks the password and issues a token. A wrong password or unknown user throws an AuthenticationException (401). */
	public LoginResponse login(LoginRequest request) {
		Authentication authentication = authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
		AppUser user = users.findByUsername(authentication.getName()).orElseThrow();

		Instant now = clock.instant();
		Instant expiresAt = now.plus(TOKEN_LIFETIME);
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(user.getId().toString())
				.issuedAt(now)
				.expiresAt(expiresAt)
				.claim(JwtUserConverter.TOKEN_VERSION, user.getTokenVersion())
				.build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new LoginResponse(token, expiresAt, UserResponse.from(user));
	}

	@Transactional(readOnly = true)
	public UserResponse me(long userId) {
		return users.findById(userId).map(UserResponse::from)
				.orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
	}

	@Transactional
	public void logout(long userId) {
		users.revokeTokens(userId);
	}

}
