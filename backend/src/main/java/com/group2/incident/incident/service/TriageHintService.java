package com.group2.incident.incident.service;

import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.incident.dto.TriageHintRequest;
import com.group2.incident.incident.dto.TriageHintResponse;
import com.group2.incident.incident.dto.TriageHintServiceDto;
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
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the AI service's /triage endpoint and maps its response to a {@link TriageHintResponse}.
 *
 * <p>AI failure, timeout, or missing configuration returns an empty {@link Optional}; the
 * controller then returns a 503 so the frontend can hide the hint without blocking declaration.
 */
@Service
@Slf4j
public class TriageHintService {

    private final URI triageEndpoint;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;

    public TriageHintService(
            @Value("${app.ai.service-url:}") String serviceUrl,
            @Value("${app.ai.triage.timeout:5s}") Duration timeout,
            JsonMapper jsonMapper) {
        this.triageEndpoint = triageEndpointFor(serviceUrl);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("AI triage timeout must be positive");
        }
        this.timeout = timeout;
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean isEnabled() {
        return triageEndpoint != null;
    }

    /**
     * Calls the AI service's /triage endpoint with similar incidents and org services.
     *
     * @return an empty Optional when AI is disabled, unavailable, or returns an unusable response.
     *         Never throws — AI failures must not block incident declaration.
     */
    public Optional<TriageHintResponse> fetchHint(
            TriageHintRequest request,
            List<?> similarIncidents,
            List<TriageHintServiceDto> orgServices) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            AiTriageRequest aiRequest = new AiTriageRequest(
                    request.title(), request.description(), similarIncidents, orgServices != null ? orgServices : List.of());
            String body = jsonMapper.writeValueAsString(aiRequest);
            HttpRequest httpRequest = HttpRequest.newBuilder(triageEndpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("AI triage service returned HTTP {}; ignoring hint", response.statusCode());
                return Optional.empty();
            }
            AiTriageResponse aiResponse = jsonMapper.readValue(response.body(), AiTriageResponse.class);
            if (aiResponse == null || aiResponse.severity() == null || aiResponse.reason() == null) {
                log.warn("AI triage service returned an unusable response");
                return Optional.empty();
            }
            Severity severity;
            try {
                severity = Severity.valueOf(aiResponse.severity().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("AI triage service returned invalid severity '{}'; ignoring hint", aiResponse.severity());
                return Optional.empty();
            }
            TriageHintServiceDto serviceDto = null;
            if (aiResponse.service() != null && aiResponse.service().id() != null && aiResponse.service().name() != null) {
                Long suggestedId = aiResponse.service().id();
                boolean isValid = orgServices != null && orgServices.stream()
                        .anyMatch(s -> suggestedId.equals(s.id()));
                if (isValid) {
                    serviceDto = new TriageHintServiceDto(suggestedId, aiResponse.service().name());
                } else {
                    log.warn("AI triage suggested service id {} not in organization services; returning null service", suggestedId);
                }
            }
            return Optional.of(new TriageHintResponse(severity, serviceDto, aiResponse.reason()));
        } catch (HttpTimeoutException e) {
            log.warn("AI triage request timed out");
            return Optional.empty();
        } catch (IOException e) {
            log.warn("AI triage service is unavailable: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("AI triage request was interrupted");
            return Optional.empty();
        } catch (JacksonException e) {
            log.warn("AI triage service returned an unreadable response");
            return Optional.empty();
        }
    }

    public Optional<TriageHintResponse> fetchHint(TriageHintRequest request, List<?> similarIncidents) {
        return fetchHint(request, similarIncidents, List.of());
    }

    private static URI triageEndpointFor(String serviceUrl) {
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
        return base.resolve("triage");
    }

    // ---------------------------------------------------------------------------
    // Internal records for JSON serialization/deserialization
    // ---------------------------------------------------------------------------

    private record AiTriageRequest(String title, String description, List<?> similar_incidents, List<?> services) {
    }

    private record AiServiceDto(Long id, String name) {
    }

    private record AiTriageResponse(boolean aiGenerated, String severity, AiServiceDto service, String reason) {
    }
}
