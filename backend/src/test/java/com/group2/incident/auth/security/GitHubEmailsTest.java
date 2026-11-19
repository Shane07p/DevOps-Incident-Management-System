package com.group2.incident.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** GitHub's email list, read from a local server that answers like api.github.com (D50). */
class GitHubEmailsTest {

	private HttpServer server;
	private final AtomicReference<String> authorization = new AtomicReference<>();

	private GitHubEmails answering(int status, String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/user/emails", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		String api = "http://127.0.0.1:" + server.getAddress().getPort();
		return new GitHubEmails(new OAuthSettings(null, null, null, api), JsonMapper.builder().build());
	}

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void thePrimaryVerifiedEmailIsUsedWithTheUsersToken() throws IOException {
		GitHubEmails emails = answering(200, """
				[{"email":"old@example.test","primary":false,"verified":true,"visibility":null},
				 {"email":"octo@example.test","primary":true,"verified":true,"visibility":"private"}]""");

		assertThat(emails.primaryVerified("gho_token")).contains("octo@example.test");
		assertThat(authorization.get()).isEqualTo("Bearer gho_token");
	}

	@Test
	void anUnverifiedPrimaryIsNotUsedEvenIfAnotherAddressIsVerified() throws IOException {
		GitHubEmails emails = answering(200, """
				[{"email":"octo@example.test","primary":true,"verified":false},
				 {"email":"other@example.test","primary":false,"verified":true}]""");

		assertThat(emails.primaryVerified("gho_token")).isEmpty();
	}

	@Test
	void anErrorOrABadBodyGivesNoEmail() throws IOException {
		assertThat(answering(401, "{\"message\":\"Bad credentials\"}").primaryVerified("gho_token")).isEmpty();
		stop();
		assertThat(answering(200, "not json").primaryVerified("gho_token")).isEmpty();
	}
}
