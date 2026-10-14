package com.group2.incident.auth;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The signed-in user, for recording who made a change. This is the one place that maps the
 * authentication to a user row, so switching from HTTP Basic to JWT (D21) only changes this class.
 */
@Component
public class CurrentUser {

	private final AppUserRepository users;

	CurrentUser(AppUserRepository users) {
		this.users = users;
	}

	public AppUser require() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			throw new AuthenticationCredentialsNotFoundException("Not signed in");
		}
		return users.findByUsername(authentication.getName())
				.orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Unknown user"));
	}

}
