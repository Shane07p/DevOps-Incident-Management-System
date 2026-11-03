package com.group2.incident.auth.service;

import com.group2.incident.auth.dto.LoginRequest;
import com.group2.incident.auth.dto.LoginResponse;
import com.group2.incident.auth.dto.MeResponse;
import com.group2.incident.auth.dto.UserResponse;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.auth.security.JwtUserConverter;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.org.dto.OrgResponse;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.MembershipRepository;
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

	private final MembershipRepository memberships;

	private final JwtEncoder jwtEncoder;

	private final Clock clock;

	/**
	 * Checks the password and issues a token for the person's org. A wrong password, an unknown email, and
	 * a disabled membership all throw the same AuthenticationException (401).
	 */
	public LoginResponse login(LoginRequest request) {
		Authentication authentication = authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
		AppUser user = users.findByEmail(authentication.getName()).orElseThrow();
		Membership membership = memberships.findFirstByUserIdAndEnabledTrue(user.getId()).orElseThrow();
		return issueToken(membership);
	}

	/** A token for one membership: it carries the org; the role is read again from the membership on every request. Used by login and sign-up. */
	public LoginResponse issueToken(Membership membership) {
		AppUser user = membership.getUser();
		Instant now = clock.instant();
		Instant expiresAt = now.plus(TOKEN_LIFETIME);
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(user.getId().toString())
				.issuedAt(now)
				.expiresAt(expiresAt)
				.claim(JwtUserConverter.TOKEN_VERSION, user.getTokenVersion())
				.claim(JwtUserConverter.ORG, membership.getOrg().getId())
				.claim(JwtUserConverter.ROLE, membership.getRole().name())
				.build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new LoginResponse(token, expiresAt, UserResponse.from(membership), OrgResponse.from(membership.getOrg()));
	}

	@Transactional(readOnly = true)
	public MeResponse me(long userId, long orgId) {
		Membership membership = memberships.findByUserIdAndOrgId(userId, orgId)
				.orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
		return new MeResponse(UserResponse.from(membership), OrgResponse.from(membership.getOrg()));
	}

	@Transactional
	public void logout(long userId) {
		users.revokeTokens(userId);
	}

}
