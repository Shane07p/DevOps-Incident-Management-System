package com.group2.incident.auth.security;

import com.group2.incident.auth.entity.OAuthProvider;
import com.group2.incident.auth.service.OAuthSignInService;
import com.group2.incident.auth.service.ProviderAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * The end of a Google or GitHub sign-in (D50). Either way the short session that carried the sign-in is thrown
 * away and the browser goes back to the app: with a one-time code on success, or to the sign-in page with a reason.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OAuthLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

	private final OAuthSignInService signIn;
	private final OAuthSettings settings;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
			throws IOException {
		String path;
		try {
			path = accountOf(authentication).map(signIn::finish).orElse("/login?oauth=failed");
		}
		catch (RuntimeException e) {
			// Never the exception's message: it may name the person.
			log.warn("A provider sign-in could not be completed: {}", e.getClass().getSimpleName());
			path = "/login?oauth=failed";
		}
		end(request, response, path);
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
			throws IOException {
		// Cancelled at the provider, a stale state, or a refused code exchange.
		log.info("A provider sign-in was refused: {}", exception.getClass().getSimpleName());
		end(request, response, "/login?oauth=failed");
	}

	private void end(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
		response.sendRedirect(settings.publicBase() + path);
	}

	/** Google answers with OpenID Connect claims; GitHub with its user, plus the verified email we looked up. */
	static Optional<ProviderAccount> accountOf(Authentication authentication) {
		if (!(authentication instanceof OAuth2AuthenticationToken token)) {
			return Optional.empty();
		}
		return OAuthProvider.fromRegistrationId(token.getAuthorizedClientRegistrationId()).map(provider -> switch (provider) {
			case GOOGLE -> google((OidcUser) token.getPrincipal());
			case GITHUB -> github(token.getPrincipal());
		});
	}

	private static ProviderAccount google(OidcUser user) {
		return new ProviderAccount(OAuthProvider.GOOGLE, user.getSubject(), user.getEmail(),
				Boolean.TRUE.equals(user.getEmailVerified()), user.getFullName());
	}

	private static ProviderAccount github(OAuth2User user) {
		Map<String, Object> attributes = user.getAttributes();
		Object id = attributes.get("id");
		Object email = attributes.get(ProviderUserService.VERIFIED_EMAIL);
		Object name = attributes.get("name") != null ? attributes.get("name") : attributes.get("login");
		return new ProviderAccount(OAuthProvider.GITHUB, String.valueOf(id), email == null ? null : email.toString(),
				email != null, name == null ? null : name.toString());
	}
}
