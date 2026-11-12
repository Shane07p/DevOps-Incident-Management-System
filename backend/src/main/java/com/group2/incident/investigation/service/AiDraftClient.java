package com.group2.incident.investigation.service;

import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.investigation.dto.DraftKind;
import com.group2.incident.investigation.dto.PostmortemDraftDto;
import com.group2.incident.investigation.service.AiInvestigationClient.IncidentEvidenceContext;
import com.group2.incident.investigation.service.AiInvestigationClient.TimelineBundleItem;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@Slf4j
public class AiDraftClient {

	private final URI draftEndpoint;

	private final Duration timeout;

	private final HttpClient httpClient;

	private final JsonMapper jsonMapper;

	public AiDraftClient(
			@Value("${app.ai.service-url:}") String serviceUrl,
			@Value("${app.ai.draft.timeout:20s}") Duration timeout,
			JsonMapper jsonMapper
	) {
		this.draftEndpoint = endpointFor(serviceUrl);
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("AI draft timeout must be positive");
		}
		this.timeout = timeout;
		this.jsonMapper = jsonMapper;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(2))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	public boolean isEnabled() {
		return draftEndpoint != null;
	}

	public AiDraftResult generateDraft(AiDraftRequest payload) {
		if (!isEnabled()) {
			throw new ServiceUnavailableException("AI drafting service is not set up on this server");
		}

		try {
			String body = jsonMapper.writeValueAsString(payload);
			HttpRequest request = HttpRequest.newBuilder(draftEndpoint)
					.timeout(timeout)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				log.warn("AI drafting service returned HTTP {}", response.statusCode());
				throw new ServiceUnavailableException("AI drafting service is unavailable right now");
			}

			AiDraftResult result = jsonMapper.readValue(response.body(), AiDraftResult.class);
			if (result == null) {
				throw new ServiceUnavailableException("AI drafting service returned an empty response");
			}
			return result;
		}
		catch (HttpTimeoutException e) {
			log.warn("AI drafting request timed out after {}s", timeout.toSeconds());
			throw new ServiceUnavailableException("AI drafting service timed out", e);
		}
		catch (IOException e) {
			log.warn("AI drafting service is unreachable: {}", e.getClass().getSimpleName());
			throw new ServiceUnavailableException("AI drafting service is unavailable right now", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("AI drafting request was interrupted");
			throw new ServiceUnavailableException("AI drafting service was interrupted", e);
		}
		catch (JacksonException e) {
			log.warn("AI drafting service returned an unreadable response");
			throw new ServiceUnavailableException("AI drafting service returned an unreadable response", e);
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
			throw new IllegalArgumentException(
					"AI service URL must be an HTTP(S) base URL without credentials or query parameters");
		}
		return base.resolve("draft");
	}

	public record AiDraftRequest(
			DraftKind kind,
			IncidentEvidenceContext incident,
			List<TimelineBundleItem> timeline,
			List<AcceptedHypothesisInput> acceptedHypotheses,
			List<String> followUps
	) {
	}

	public record AcceptedHypothesisInput(
			int rank,
			String title,
			double confidence
	) {
	}

	public record AiDraftResult(
			boolean aiGenerated,
			DraftKind kind,
			String text,
			PostmortemDraftDto postmortem
	) {
	}
}
