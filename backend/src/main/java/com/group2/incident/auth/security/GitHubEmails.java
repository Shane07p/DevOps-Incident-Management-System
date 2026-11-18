package com.group2.incident.auth.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a GitHub user's primary verified email with their sign-in token. GitHub's /user shows only a public email,
 * which may be missing or unverified, so it can't prove who someone is.
 */
@Slf4j
@Component
class GitHubEmails {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final URI emailsEndpoint;
	private final JsonMapper jsonMapper;
	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(TIMEOUT)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	GitHubEmails(OAuthSettings settings, JsonMapper jsonMapper) {
		String base = settings.githubApiUrl() == null || settings.githubApiUrl().isBlank()
				? "https://api.github.com" : settings.githubApiUrl().trim();
		this.emailsEndpoint = URI.create((base.endsWith("/") ? base : base + "/") + "user/emails");
		this.jsonMapper = jsonMapper;
	}

	private record Email(String email, boolean primary, boolean verified) {
	}

	/** The primary address if GitHub has verified it; empty otherwise, or when GitHub can't be read. */
	Optional<String> primaryVerified(String accessToken) {
		HttpRequest request = HttpRequest.newBuilder(emailsEndpoint)
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + accessToken)
				.header("Accept", "application/vnd.github+json")
				.header("X-GitHub-Api-Version", "2022-11-28")
				.GET()
				.build();
		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.warn("GitHub answered HTTP {} for the user's emails", response.statusCode());
				return Optional.empty();
			}
			List<Email> emails = List.of(jsonMapper.readValue(response.body(), Email[].class));
			return emails.stream()
					.filter(email -> email.primary() && email.verified() && email.email() != null)
					.map(Email::email)
					.findFirst();
		}
		catch (IOException | JacksonException e) {
			log.warn("GitHub's email list could not be read: {}", e.getClass().getSimpleName());
			return Optional.empty();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}
}
