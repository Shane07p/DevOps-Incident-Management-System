package com.group2.incident.auth.service;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.auth.security.JwtUserConverter;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.MembershipRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Used only by login, to check the password. Requests with a token go through {@link JwtUserConverter}.
 * An unknown email and a person with no enabled membership look the same: Spring answers both as a bad
 * password, so the 401 never says which.
 */
@Service
@RequiredArgsConstructor
class AppUserDetailsService implements UserDetailsService {

	private final AppUserRepository users;

	private final MembershipRepository memberships;

	@Override
	public UserDetails loadUserByUsername(String email) {
		AppUser user = users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
		Membership membership = memberships.findFirstByUserIdAndEnabledTrue(user.getId())
				.orElseThrow(() -> new UsernameNotFoundException("No active membership"));
		return User.withUsername(user.getEmail())
				// A Google-only account has no password; a random one makes every password attempt fail.
				.password(user.getPasswordHash() == null ? "{noop}" + UUID.randomUUID() : user.getPasswordHash())
				.roles(membership.getRole().name())
				.build();
	}

}
