package com.group2.incident.investigation.service;

import com.group2.incident.common.error.ServiceUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@Slf4j
public class AiInvestigationClient {

	private final URI investigateEndpoint;

	private final Duration timeout;

	private final HttpClient httpClient;

	private final JsonMapper jsonMapper;

	public AiInvestigationClient(
			@Value("${app.ai.service-url:}") String serviceUrl,
			@Value("${app.ai.investigation.timeout:20s}") Duration timeout,
			JsonMapper jsonMapper
	) {
		this.investigateEndpoint = endpointFor(serviceUrl);
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("AI investigation timeout must be positive");
		}
		this.timeout = timeout;
		this.jsonMapper = jsonMapper;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(2))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	public boolean isEnabled() {
		return investigateEndpoint != null;
	}

	public AiInvestigationResult investigate(AiInvestigationRequest payload) {
		if (!isEnabled()) {
			throw new ServiceUnavailableException("AI investigation service is not set up on this server");
		}

		try {
			String body = jsonMapper.writeValueAsString(payload);
			HttpRequest request = HttpRequest.newBuilder(investigateEndpoint)
					.timeout(timeout)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				log.warn("AI investigation service returned HTTP {}", response.statusCode());
				throw new ServiceUnavailableException("AI investigation service is unavailable right now");
			}

			AiInvestigationResult result = jsonMapper.readValue(response.body(), AiInvestigationResult.class);
			if (result == null || "provider_error".equals(result.status()) || "timeout".equals(result.status())) {
				log.warn("AI investigation could not complete: status={}", result != null ? result.status() : "null");
				throw new ServiceUnavailableException("AI investigation service is unavailable or timed out");
			}
			return result;
		}
		catch (HttpTimeoutException e) {
			log.warn("AI investigation request timed out after {}s", timeout.toSeconds());
			throw new ServiceUnavailableException("AI investigation service timed out", e);
		}
		catch (IOException e) {
			log.warn("AI investigation service is unreachable: {}", e.getClass().getSimpleName());
			throw new ServiceUnavailableException("AI investigation service is unavailable right now", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("AI investigation request was interrupted");
			throw new ServiceUnavailableException("AI investigation service was interrupted", e);
		}
		catch (JacksonException e) {
			log.warn("AI investigation service returned an unreadable response");
			throw new ServiceUnavailableException("AI investigation service returned an unreadable response", e);
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
		return base.resolve("investigate");
	}

	public record AiInvestigationRequest(
			String service,
			String incidentStart,
			String incidentEnd,
			String description,
			EvidenceBundle evidenceBundle
	) {
	}

	public record EvidenceBundle(
			IncidentEvidenceContext incident,
			List<TimelineBundleItem> timeline,
			List<DeployBundleItem> deploys,
			List<LogBundleItem> logs,
			List<MetricBundleSeries> metrics
	) {
	}

	public record IncidentEvidenceContext(
			Long id,
			String reference,
			String title,
			String description,
			String service,
			String severity,
			String status,
			Instant createdAt
	) {
	}

	public record TimelineBundleItem(
			Long id,
			String eventType,
			String actor,
			String details,
			Instant recordedAt
	) {
	}

	public record DeployBundleItem(
			Long id,
			String service,
			String version,
			String commit,
			String author,
			Instant deployedAt
	) {
	}

	public record LogBundleItem(
			String id,
			Instant timestamp,
			String level,
			String message,
			Map<String, Object> attributes
	) {
	}

	public record MetricBundlePoint(
			Instant timestamp,
			double value
	) {
	}

	public record MetricBundleSeries(
			String metric,
			String serviceName,
			List<MetricBundlePoint> points
	) {
	}

	public record AiInvestigationResult(
			boolean aiGenerated,
			String status,
			String summary,
			List<AiHypothesis> hypotheses,
			List<AiEvidenceItem> evidence,
			List<String> suggestedActions,
			int stepsTaken,
			int tokensUsed,
			double durationMs
	) {
	}

	public record AiHypothesis(
			String hypothesis,
			double confidence,
			List<String> evidenceRefs,
			String reasoning
	) {
	}

	public record AiEvidenceItem(
			String id,
			String sourceTool,
			String evidenceType,
			String content
	) {
	}
}
