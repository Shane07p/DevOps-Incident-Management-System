package com.settle.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/** A sign-in starts only for a provider that is set up; any other is a plain 404, not a 500 (D50). */
class KnownProvidersOnlyTest {

	private static final String BASE = "/api/auth/oauth2/authorization";

	private final ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
			.clientId("google-client").clientSecret("secret")
			.redirectUri("https://settle.test/api/auth/oauth2/callback/google").build();

	private final KnownProvidersOnly resolver = new KnownProvidersOnly(id -> "google".equals(id) ? google : null, BASE);

	private static MockHttpServletRequest start(String provider) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", BASE + "/" + provider);
		request.setServletPath(BASE + "/" + provider);
		return request;
	}

	@Test
	void aProviderThatIsSetUpGetsAnAuthorizationRequest() {
		assertThat(resolver.resolve(start("google"))).isNotNull()
				.satisfies(request -> assertThat(request.getClientId()).isEqualTo("google-client"));
		assertThat(resolver.resolve(start("google"), "google")).isNotNull();
	}

	@Test
	void anyOtherProviderGetsNothingInsteadOfAnError() {
		assertThat(resolver.resolve(start("gitlab"))).isNull();
		assertThat(resolver.resolve(start("gitlab"), "gitlab")).isNull();
	}
}
