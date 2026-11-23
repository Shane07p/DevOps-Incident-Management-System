package com.settle.auth.security;

import com.settle.auth.entity.OAuthProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign in with Google and GitHub (D50). A provider is on only when both its client id and secret are set, so a
 * deployment without them still starts and simply hides the button.
 *
 * @param publicUrl the address people open (the frontend, which proxies /api to the backend). Callbacks and the
 *        redirects after a sign-in are built on it, never on the request's own host.
 */
@ConfigurationProperties(prefix = "app.oauth")
public record OAuthSettings(String publicUrl, Client google, Client github, String githubApiUrl) {

	public record Client(String clientId, String clientSecret) {
		boolean configured() {
			return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
		}
	}

	public String publicBase() {
		String base = publicUrl == null ? "" : publicUrl.trim();
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	public String callbackUri(OAuthProvider provider) {
		return publicBase() + "/api/auth/oauth2/callback/" + provider.registrationId();
	}

	Client client(OAuthProvider provider) {
		return switch (provider) {
			case GOOGLE -> google;
			case GITHUB -> github;
		};
	}

	public List<OAuthProvider> enabled() {
		List<OAuthProvider> enabled = new ArrayList<>();
		for (OAuthProvider provider : OAuthProvider.values()) {
			Client client = client(provider);
			if (client != null && client.configured()) {
				enabled.add(provider);
			}
		}
		return enabled;
	}
}
