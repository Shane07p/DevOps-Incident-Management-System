package com.group2.incident.incident.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@Slf4j
public class AiEmbeddingClient {

	static final int EMBEDDING_DIMENSIONS = 768;

	private final URI embeddingEndpoint;

	private final Duration timeout;

	private final HttpClient httpClient;

	private final JsonMapper jsonMapper;

	public AiEmbeddingClient(
			@Value("${app.ai.service-url:}") String serviceUrl,
			@Value("${app.ai.embedding.timeout:5s}") Duration timeout,
			JsonMapper jsonMapper
	) {
		this.embeddingEndpoint = endpointFor(serviceUrl);
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("AI embedding timeout must be positive");
		}
		this.timeout = timeout;
		this.jsonMapper = jsonMapper;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(2))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	public boolean isEnabled() {
		return embeddingEndpoint != null;
	}

	public Optional<List<Double>> embedDocument(String text) {
		if (!isEnabled()) {
			return Optional.empty();
		}

		try {
			String body = jsonMapper.writeValueAsString(new EmbeddingRequest(text, "document"));
			HttpRequest request = HttpRequest.newBuilder(embeddingEndpoint)
					.timeout(timeout)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				log.warn("AI service returned HTTP {}; the incident embedding will be retried", response.statusCode());
				return Optional.empty();
			}

			EmbeddingResponse result = jsonMapper.readValue(response.body(), EmbeddingResponse.class);
			if (!isValidEmbedding(result)) {
				log.warn("AI service returned an invalid embedding; the incident embedding will be retried");
				return Optional.empty();
			}
			return Optional.of(result.embedding());
		}
		catch (HttpTimeoutException e) {
			log.warn("AI embedding request timed out; the incident embedding will be retried");
			return Optional.empty();
		}
		catch (IOException e) {
			log.warn("AI embedding service is unavailable; the incident embedding will be retried");
			return Optional.empty();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("AI embedding request was interrupted; the incident embedding will be retried");
			return Optional.empty();
		}
		catch (JacksonException e) {
			log.warn("AI service returned an unreadable embedding; the incident embedding will be retried");
			return Optional.empty();
		}
	}

	private static URI endpointFor(String serviceUrl) {
		if (serviceUrl == null || serviceUrl.isBlank()) {
			return null;
		}
		String normalized = serviceUrl.trim();
		URI base = URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
		if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
				|| base.getHost() == null || base.getUserInfo() != null
				|| base.getQuery() != null || base.getFragment() != null) {
			throw new IllegalArgumentException("AI service URL must be an HTTP(S) base URL without credentials or query parameters");
		}
		return base.resolve("embed");
	}

	private static boolean isValidEmbedding(EmbeddingResponse response) {
		return response != null
				&& response.embedding() != null
				&& response.embedding().size() == EMBEDDING_DIMENSIONS
				&& response.embedding().stream().allMatch(value -> value != null
						&& Double.isFinite(value)
						&& Math.abs(value) <= Float.MAX_VALUE);
	}

	private record EmbeddingRequest(String text, String kind) {
	}

	private record EmbeddingResponse(List<Double> embedding, String model) {
	}
}
