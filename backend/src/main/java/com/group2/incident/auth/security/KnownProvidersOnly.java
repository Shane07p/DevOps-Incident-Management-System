package com.group2.incident.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Starts a sign-in only for a provider that is set up (D50). Spring's resolver throws for an unknown one, which
 * the redirect filter turns into a 500; returning null lets the request fall through to a plain 404 instead.
 */
final class KnownProvidersOnly implements OAuth2AuthorizationRequestResolver {

	private final DefaultOAuth2AuthorizationRequestResolver delegate;

	KnownProvidersOnly(ClientRegistrationRepository registrations, String authorizationBaseUri) {
		this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, authorizationBaseUri);
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		try {
			return delegate.resolve(request);
		}
		catch (IllegalArgumentException unknownProvider) {
			return null;
		}
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
		try {
			return delegate.resolve(request, clientRegistrationId);
		}
		catch (IllegalArgumentException unknownProvider) {
			return null;
		}
	}
}
