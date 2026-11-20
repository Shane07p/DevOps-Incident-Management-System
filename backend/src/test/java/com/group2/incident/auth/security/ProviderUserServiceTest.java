package com.group2.incident.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loading a GitHub user (D50), from a local server that answers /user and /user/emails like api.github.com: the
 * profile comes from /user, and the primary verified email from /user/emails is added to it.
 */
class ProviderUserServiceTest {

	private HttpServer server;
	private String base;
	private final List<String> requested = new ArrayList<>();

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		answer("/user", """
				{"id":583231,"login":"octo","name":"Octo Cat","email":null}""");
		answer("/user/emails", """
				[{"email":"octo@example.test","primary":true,"verified":true}]""");
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private void answer(String path, String body) {
		server.createContext(path, exchange -> {
			requested.add(exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private ProviderUserService service() {
		return new ProviderUserService(new GitHubEmails(new OAuthSettings(null, null, null, base), JsonMapper.builder().build()));
	}

	private OAuth2UserRequest request(String registrationId, ClientRegistration.Builder builder) {
		ClientRegistration registration = builder.clientId("id").clientSecret("secret")
				.redirectUri("https://settle.test/api/auth/oauth2/callback/" + registrationId)
				.userInfoUri(base + "/user")
				.build();
		OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "gho_token",
				Instant.parse("2026-11-18T10:00:00Z"), Instant.parse("2026-11-18T11:00:00Z"));
		return new OAuth2UserRequest(registration, token);
	}

	@Test
	void aGitHubUserGetsTheirPrimaryVerifiedEmailAdded() {
		OAuth2User user = service().loadUser(request("github", CommonOAuth2Provider.GITHUB.getBuilder("github")));

		assertThat(user.getName()).isEqualTo("583231");
		assertThat(user.<String>getAttribute("login")).isEqualTo("octo");
		assertThat(user.<String>getAttribute(ProviderUserService.VERIFIED_EMAIL)).isEqualTo("octo@example.test");
		assertThat(requested).containsExactly("/user Bearer gho_token", "/user/emails Bearer gho_token");
	}

	@Test
	void anotherProviderIsLoadedAsItIsWithoutAskingGitHubForEmails() {
		ClientRegistration.Builder other = CommonOAuth2Provider.GITHUB.getBuilder("other");

		OAuth2User user = service().loadUser(request("other", other));

		assertThat(user.getAttributes()).doesNotContainKey(ProviderUserService.VERIFIED_EMAIL);
		assertThat(requested).containsExactly("/user Bearer gho_token");
	}

	@Test
	void withoutAVerifiedPrimaryEmailNoneIsAdded() {
		server.removeContext("/user/emails");
		answer("/user/emails", """
				[{"email":"octo@example.test","primary":true,"verified":false}]""");

		OAuth2User user = service().loadUser(request("github", CommonOAuth2Provider.GITHUB.getBuilder("github")));

		assertThat(user.getAttributes()).doesNotContainKey(ProviderUserService.VERIFIED_EMAIL).containsEntry("login", "octo");
	}
}
