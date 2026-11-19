package com.group2.incident.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.group2.incident.auth.entity.OAuthProvider;
import com.group2.incident.auth.service.OAuthSignInService;
import com.group2.incident.auth.service.ProviderAccount;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/** What Google and GitHub send back, read into a {@link ProviderAccount} (D50). */
class OAuthLoginHandlerTest {

	private static final List<SimpleGrantedAuthority> USER = List.of(new SimpleGrantedAuthority("OAUTH2_USER"));

	private static OAuth2AuthenticationToken google(boolean verified) {
		OidcIdToken idToken = new OidcIdToken("id-token", Instant.parse("2026-11-18T10:00:00Z"), Instant.parse("2026-11-18T11:00:00Z"),
				Map.of("sub", "1098765", "email", "asha@example.test", "email_verified", verified, "name", "Asha Patel"));
		return new OAuth2AuthenticationToken(new DefaultOidcUser(USER, idToken), USER, "google");
	}

	private static OAuth2AuthenticationToken github(String verifiedEmail, String name) {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("id", 583231);
		attributes.put("login", "octo");
		attributes.put("name", name);
		if (verifiedEmail != null) {
			attributes.put(ProviderUserService.VERIFIED_EMAIL, verifiedEmail);
		}
		return new OAuth2AuthenticationToken(new DefaultOAuth2User(USER, attributes, "id"), USER, "github");
	}

	@Test
	void googleGivesItsSubjectEmailAndWhetherItVerifiedTheEmail() {
		assertThat(OAuthLoginHandler.accountOf(google(true))).contains(
				new ProviderAccount(OAuthProvider.GOOGLE, "1098765", "asha@example.test", true, "Asha Patel"));
		assertThat(OAuthLoginHandler.accountOf(google(false)).orElseThrow().emailVerified()).isFalse();
	}

	@Test
	void githubGivesItsNumericIdAndOnlyTheVerifiedEmailWeLookedUp() {
		assertThat(OAuthLoginHandler.accountOf(github("octo@example.test", "Octo Cat"))).contains(
				new ProviderAccount(OAuthProvider.GITHUB, "583231", "octo@example.test", true, "Octo Cat"));
		// No name on the profile: the login stands in. No verified email: refused later as unverified.
		ProviderAccount noEmail = OAuthLoginHandler.accountOf(github(null, null)).orElseThrow();
		assertThat(noEmail.displayName()).isEqualTo("octo");
		assertThat(noEmail.email()).isNull();
		assertThat(noEmail.emailVerified()).isFalse();
	}

	@Test
	void anUnknownProviderGivesNoAccount() {
		OAuth2AuthenticationToken gitlab = new OAuth2AuthenticationToken(
				new DefaultOAuth2User(USER, Map.of("id", 1), "id"), USER, "gitlab");

		assertThat(OAuthLoginHandler.accountOf(gitlab)).isEmpty();
	}

	private final OAuthSignInService signIn = mock(OAuthSignInService.class);
	private final OAuthLoginHandler handler = new OAuthLoginHandler(signIn,
			new OAuthSettings("https://settle.test/", null, null, null));

	@Test
	void aFinishedSignInGoesToWhereTheServiceSaysAndEndsTheShortSession() throws Exception {
		when(signIn.finish(any())).thenReturn("/auth/callback#code=abc");
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.getSession(true);
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, google(true));

		assertThat(response.getRedirectedUrl()).isEqualTo("https://settle.test/auth/callback#code=abc");
		assertThat(request.getSession(false)).isNull();
	}

	@Test
	void aSignInThatBreaksOrIsRefusedGoesBackToTheSignInPage() throws Exception {
		when(signIn.finish(any())).thenThrow(new IllegalStateException("database down"));
		MockHttpServletResponse broken = new MockHttpServletResponse();
		handler.onAuthenticationSuccess(new MockHttpServletRequest(), broken, google(true));
		assertThat(broken.getRedirectedUrl()).isEqualTo("https://settle.test/login?oauth=failed");

		MockHttpServletResponse refused = new MockHttpServletResponse();
		handler.onAuthenticationFailure(new MockHttpServletRequest(), refused, new BadCredentialsException("access_denied"));
		assertThat(refused.getRedirectedUrl()).isEqualTo("https://settle.test/login?oauth=failed");
	}
}
