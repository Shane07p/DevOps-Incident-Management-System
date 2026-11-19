package com.group2.incident.auth.security;

import java.util.HashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Loads a GitHub user (Google is OpenID Connect and has its own service), and adds their primary verified email as
 * {@link #VERIFIED_EMAIL}. Without it the sign-in is refused as unverified.
 */
@Component
class ProviderUserService extends DefaultOAuth2UserService {

	static final String VERIFIED_EMAIL = "settle_verified_email";

	private final GitHubEmails gitHubEmails;

	ProviderUserService(GitHubEmails gitHubEmails) {
		this.gitHubEmails = gitHubEmails;
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest userRequest) {
		OAuth2User user = super.loadUser(userRequest);
		if (!"github".equals(userRequest.getClientRegistration().getRegistrationId())) {
			return user;
		}
		Map<String, Object> attributes = new HashMap<>(user.getAttributes());
		gitHubEmails.primaryVerified(userRequest.getAccessToken().getTokenValue())
				.ifPresent(email -> attributes.put(VERIFIED_EMAIL, email));
		return new DefaultOAuth2User(user.getAuthorities(), attributes, "id");
	}
}
