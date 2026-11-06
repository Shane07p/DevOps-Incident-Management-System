package com.group2.incident.auth.security;

import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.MembershipRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Runs on every request whose token has a valid signature. Refuses the token if it names no org, if the
 * person is no longer an enabled member of that org, or has logged out since it was issued. The role
 * comes from the membership, not the token, so a role change applies at once.
 */
@Component
@RequiredArgsConstructor
public class JwtUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	public static final String TOKEN_VERSION = "tv";

	public static final String ORG = "org";

	public static final String ROLE = "role";

	private final MembershipRepository memberships;

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		if (!(jwt.getClaim(ORG) instanceof Number org)) {
			throw new InvalidBearerTokenException("Token is no longer valid");
		}
		Membership membership = memberships.findByUserIdAndOrgId(Long.valueOf(jwt.getSubject()), org.longValue())
				.filter(Membership::isEnabled)
				// Numbers in a JWT decode as Long.
				.filter(m -> jwt.getClaim(TOKEN_VERSION) instanceof Number version && version.intValue() == m.getUser().getTokenVersion())
				.orElseThrow(() -> new InvalidBearerTokenException("Token is no longer valid"));
		return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + membership.getRole().name())));
	}

}
