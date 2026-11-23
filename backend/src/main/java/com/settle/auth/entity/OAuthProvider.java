package com.settle.auth.entity;

import java.util.Locale;
import java.util.Optional;

/** The sign-in providers (D50). The registration id in URLs is the lower-case name: /oauth2/authorization/google. */
public enum OAuthProvider {
	GOOGLE,
	GITHUB;

	public String registrationId() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static Optional<OAuthProvider> fromRegistrationId(String registrationId) {
		for (OAuthProvider provider : values()) {
			if (provider.registrationId().equals(registrationId)) {
				return Optional.of(provider);
			}
		}
		return Optional.empty();
	}
}
