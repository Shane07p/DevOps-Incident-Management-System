package com.group2.incident.telemetry.service;

import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.telemetry.config.TelemetryProperties;
import com.group2.incident.telemetry.dto.LogEntryResponse;
import com.group2.incident.telemetry.dto.MetricDataPoint;
import com.group2.incident.telemetry.dto.ServiceMetricsResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Service to query logs from Grafana Loki (FR-26) and metrics from Prometheus (FR-27).
 * Uses a server-side read token (D34); tokens are never returned to clients or logged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetryService {

	private static final Duration MAX_TIME_RANGE = Duration.ofHours(24);

	private final TelemetryProperties properties;
	private final ServiceRepository serviceRepository;
	private final JsonMapper objectMapper;
	private final Clock clock;

	/**
	 * Validate that a query's time range is valid and does not exceed the 24-hour limit (FR-26.2).
	 */
	public void validateTimeRange(Instant from, Instant to) {
		if (from != null && to != null) {
			if (to.isBefore(from)) {
				throw new FieldValidationException("to", "End time must be after start time");
			}
			Duration duration = Duration.between(from, to);
			if (duration.compareTo(MAX_TIME_RANGE) > 0) {
				throw new FieldValidationException("from", "Time range cannot exceed 24 hours");
			}
		}
	}

	/**
	 * Builds a safe LogQL query string. User text is escaped as a literal line filter
	 * so it never becomes query syntax (FR-26.1, TC-FR-26.1a).
	 */
	public String buildLogQuery(String service, String level, String text) {
		StringBuilder q = new StringBuilder("{");
		List<String> selectors = new ArrayList<>();
		if (service != null && !service.isBlank() && !service.equalsIgnoreCase("_all")) {
			selectors.add("service=\"" + escapeLabel(service.trim()) + "\"");
		}
		if (level != null && !level.isBlank() && !level.equalsIgnoreCase("_all")) {
			selectors.add("level=\"" + escapeLabel(level.trim().toUpperCase()) + "\"");
		}
		if (selectors.isEmpty()) {
			q.append("service=~\".+\"");
		} else {
			q.append(String.join(", ", selectors));
		}
		q.append("}");

		if (text != null && !text.isBlank()) {
			q.append(" |= \"").append(escapeLiteral(text.trim())).append("\"");
		}
		return q.toString();
	}

	public String buildRequestRateQuery(String service) {
		if (service != null && !service.isBlank() && !service.equalsIgnoreCase("_all")) {
			return "sum(rate(demo_http_requests_total{service=\"" + escapeLabel(service.trim()) + "\"}[5m]))";
		}
		return "sum(rate(demo_http_requests_total[5m]))";
	}

	public String buildErrorRateQuery(String service) {
		if (service != null && !service.isBlank() && !service.equalsIgnoreCase("_all")) {
			return "sum(rate(demo_http_requests_total{service=\"" + escapeLabel(service.trim()) + "\",status=~\"5..\"}[5m])) / sum(rate(demo_http_requests_total{service=\"" + escapeLabel(service.trim()) + "\"}[5m]))";
		}
		return "sum(rate(demo_http_requests_total{status=~\"5..\"}[5m])) / sum(rate(demo_http_requests_total[5m]))";
	}

	public String buildLatencyP50Query(String service) {
		if (service != null && !service.isBlank() && !service.equalsIgnoreCase("_all")) {
			return "histogram_quantile(0.50, sum(rate(demo_http_request_duration_seconds_bucket{service=\"" + escapeLabel(service.trim()) + "\"}[5m])) by (le))";
		}
		return "histogram_quantile(0.50, sum(rate(demo_http_request_duration_seconds_bucket[5m])) by (le))";
	}

	public String buildLatencyP95Query(String service) {
		if (service != null && !service.isBlank() && !service.equalsIgnoreCase("_all")) {
			return "histogram_quantile(0.95, sum(rate(demo_http_request_duration_seconds_bucket{service=\"" + escapeLabel(service.trim()) + "\"}[5m])) by (le))";
		}
		return "histogram_quantile(0.95, sum(rate(demo_http_request_duration_seconds_bucket[5m])) by (le))";
	}

	public static String escapeLabel(String label) {
		if (label == null) {
			return "";
		}
		return label.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	public static String escapeLiteral(String text) {
		if (text == null) {
			return "";
		}
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/**
	 * Search logs in Grafana Loki by service, level, text, and time range (FR-26.1).
	 */
	public List<LogEntryResponse> queryLogs(
			Long serviceId,
			String serviceName,
			String level,
			String text,
			Instant from,
			Instant to,
			Integer limit
	) {
		Instant effectiveTo = to != null ? to : clock.instant();
		Instant effectiveFrom = from != null ? from : effectiveTo.minus(Duration.ofHours(1));
		validateTimeRange(effectiveFrom, effectiveTo);

		String resolvedServiceName = serviceName;
		Long resolvedServiceId = serviceId;

		if (serviceId != null) {
			ServiceEntity entity = serviceRepository.findById(serviceId)
					.orElseThrow(() -> new NotFoundException("Service not found"));
			resolvedServiceName = entity.getName().toLowerCase();
		} else if (serviceName != null && !serviceName.isBlank() && !serviceName.equalsIgnoreCase("_all")) {
			var opt = serviceRepository.findByNameIgnoreCase(serviceName.trim());
			if (opt.isPresent()) {
				resolvedServiceId = opt.get().getId();
				resolvedServiceName = opt.get().getName().toLowerCase();
			}
		}

		if (properties.lokiUrl() == null || properties.lokiUrl().isBlank()) {
			log.debug("Loki URL is not configured; returning empty logs");
			return Collections.emptyList();
		}

		String logQuery = buildLogQuery(resolvedServiceName, level, text);
		long startNanos = effectiveFrom.toEpochMilli() * 1_000_000L;
		long endNanos = effectiveTo.toEpochMilli() * 1_000_000L;
		int maxLimit = Math.min(Math.max(limit != null ? limit : 100, 1), 1000);

		RestClient client = createLokiClient();
		String responseBody;
		try {
			responseBody = client.get()
					.uri(uriBuilder -> uriBuilder
							.path("/loki/api/v1/query_range")
							.queryParam("query", logQuery)
							.queryParam("start", String.valueOf(startNanos))
							.queryParam("end", String.valueOf(endNanos))
							.queryParam("limit", maxLimit)
							.queryParam("direction", "BACKWARD")
							.build())
					.retrieve()
					.body(String.class);
		} catch (ResourceAccessException ex) {
			log.error("Loki query timed out or connection failed: {}", ex.getMessage());
			throw new ServiceUnavailableException("Log store is slow or unavailable", ex);
		} catch (RestClientException ex) {
			log.error("Loki query failed: {}", ex.getMessage());
			throw new ServiceUnavailableException("Log store is slow or unavailable", ex);
		} catch (Exception ex) {
			log.error("Unexpected error querying Loki: {}", ex.getMessage());
			throw new ServiceUnavailableException("Log store is slow or unavailable", ex);
		}

		return parseLokiStreams(responseBody, resolvedServiceName, resolvedServiceId, maxLimit);
	}

	/**
	 * Query service metric dashboards from Prometheus (FR-27.1).
	 */
	public ServiceMetricsResponse queryMetrics(
			Long serviceId,
			String serviceName,
			Instant from,
			Instant to,
			String step
	) {
		Instant effectiveTo = to != null ? to : clock.instant();
		Instant effectiveFrom = from != null ? from : effectiveTo.minus(Duration.ofHours(1));
		validateTimeRange(effectiveFrom, effectiveTo);

		String resolvedServiceName = serviceName != null ? serviceName : "";
		Long resolvedServiceId = serviceId;

		if (serviceId != null) {
			ServiceEntity entity = serviceRepository.findById(serviceId)
					.orElseThrow(() -> new NotFoundException("Service not found"));
			resolvedServiceName = entity.getName().toLowerCase();
		} else if (serviceName != null && !serviceName.isBlank() && !serviceName.equalsIgnoreCase("_all")) {
			var opt = serviceRepository.findByNameIgnoreCase(serviceName.trim());
			if (opt.isPresent()) {
				resolvedServiceId = opt.get().getId();
				resolvedServiceName = opt.get().getName().toLowerCase();
			}
		}

		if (properties.prometheusUrl() == null || properties.prometheusUrl().isBlank()) {
			log.debug("Prometheus URL is not configured; returning empty metrics");
			return new ServiceMetricsResponse(
					resolvedServiceId,
					resolvedServiceName,
					effectiveFrom,
					effectiveTo,
					Collections.emptyList(),
					Collections.emptyList(),
					Collections.emptyList(),
					Collections.emptyList()
			);
		}

		String effectiveStep = (step != null && !step.isBlank()) ? step : "60s";

		List<MetricDataPoint> requestRate = executePrometheusQuery(
				buildRequestRateQuery(resolvedServiceName), effectiveFrom, effectiveTo, effectiveStep);
		List<MetricDataPoint> errorRate = executePrometheusQuery(
				buildErrorRateQuery(resolvedServiceName), effectiveFrom, effectiveTo, effectiveStep);
		List<MetricDataPoint> latencyP50 = executePrometheusQuery(
				buildLatencyP50Query(resolvedServiceName), effectiveFrom, effectiveTo, effectiveStep);
		List<MetricDataPoint> latencyP95 = executePrometheusQuery(
				buildLatencyP95Query(resolvedServiceName), effectiveFrom, effectiveTo, effectiveStep);

		return new ServiceMetricsResponse(
				resolvedServiceId,
				resolvedServiceName,
				effectiveFrom,
				effectiveTo,
				requestRate,
				errorRate,
				latencyP50,
				latencyP95
		);
	}

	/**
	 * Query a single metric series by name (requestRate, errorRate, latencyP50, latencyP95) (TC-FR-27.1a).
	 */
	public List<MetricDataPoint> queryMetric(
			String metricType,
			String serviceName,
			Instant from,
			Instant to,
			String step
	) {
		Instant effectiveTo = to != null ? to : clock.instant();
		Instant effectiveFrom = from != null ? from : effectiveTo.minus(Duration.ofHours(1));
		validateTimeRange(effectiveFrom, effectiveTo);

		String query = switch (metricType) {
			case "requestRate" -> buildRequestRateQuery(serviceName);
			case "errorRate" -> buildErrorRateQuery(serviceName);
			case "latencyP50" -> buildLatencyP50Query(serviceName);
			case "latencyP95" -> buildLatencyP95Query(serviceName);
			default -> throw new FieldValidationException("metric", "Unknown metric: " + metricType);
		};

		String effectiveStep = (step != null && !step.isBlank()) ? step : "60s";
		return executePrometheusQuery(query, effectiveFrom, effectiveTo, effectiveStep);
	}

	private List<MetricDataPoint> executePrometheusQuery(String promQuery, Instant from, Instant to, String step) {
		if (properties.prometheusUrl() == null || properties.prometheusUrl().isBlank()) {
			return Collections.emptyList();
		}

		RestClient client = createPrometheusClient();
		String responseBody;
		try {
			responseBody = client.get()
					.uri(uriBuilder -> uriBuilder
							.path(resolvePrometheusPath())
							.queryParam("query", promQuery)
							.queryParam("start", String.valueOf(from.getEpochSecond()))
							.queryParam("end", String.valueOf(to.getEpochSecond()))
							.queryParam("step", step)
							.build())
					.retrieve()
					.body(String.class);
		} catch (ResourceAccessException ex) {
			log.error("Prometheus query timed out or connection failed: {}", ex.getMessage());
			throw new ServiceUnavailableException("Metrics store is slow or unavailable", ex);
		} catch (RestClientException ex) {
			log.error("Prometheus query failed: {}", ex.getMessage());
			throw new ServiceUnavailableException("Metrics store is slow or unavailable", ex);
		} catch (Exception ex) {
			log.error("Unexpected error querying Prometheus: {}", ex.getMessage());
			throw new ServiceUnavailableException("Metrics store is slow or unavailable", ex);
		}

		return parsePrometheusMatrix(responseBody);
	}

	private String resolvePrometheusPath() {
		String baseUrl = properties.prometheusUrl();
		if (baseUrl.endsWith("/api/v1") || baseUrl.endsWith("/api/v1/")) {
			return "/query_range";
		}
		return "/api/v1/query_range";
	}

	private List<LogEntryResponse> parseLokiStreams(
			String responseBody,
			String defaultServiceName,
			Long serviceId,
			int limit
	) {
		List<LogEntryResponse> logs = new ArrayList<>();
		if (responseBody == null || responseBody.isBlank()) {
			return logs;
		}

		try {
			JsonNode root = objectMapper.readTree(responseBody);
			JsonNode result = root.path("data").path("result");
			if (result.isArray()) {
				for (JsonNode streamNode : result) {
					JsonNode streamLabels = streamNode.path("stream");
					String streamService = streamLabels.has("service")
							? streamLabels.get("service").asText()
							: (defaultServiceName != null ? defaultServiceName : "");
					String streamLevel = streamLabels.has("level")
							? streamLabels.get("level").asText()
							: "INFO";

					JsonNode values = streamNode.path("values");
					if (values.isArray()) {
						for (JsonNode valNode : values) {
							if (valNode.isArray() && valNode.size() >= 2) {
								String nanoStr = valNode.get(0).asText();
								String line = valNode.get(1).asText();

								Instant ts;
								try {
									long nanos = Long.parseLong(nanoStr);
									long epochSec = nanos / 1_000_000_000L;
									long remNanos = nanos % 1_000_000_000L;
									ts = Instant.ofEpochSecond(epochSec, remNanos);
								} catch (Exception e) {
									ts = clock.instant();
								}

								String logMsg = line;
								String logLevel = streamLevel;
								Map<String, Object> attributes = new HashMap<>();

								String trimmed = line.trim();
								if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
									try {
										@SuppressWarnings("unchecked")
										Map<String, Object> map = objectMapper.readValue(line, Map.class);
										if (map != null) {
											if (map.containsKey("message") && map.get("message") != null) {
												logMsg = String.valueOf(map.get("message"));
											}
											if (map.containsKey("level") && map.get("level") != null) {
												logLevel = String.valueOf(map.get("level"));
											}
											if (map.containsKey("timestamp") && map.get("timestamp") != null) {
												try {
													ts = Instant.parse(String.valueOf(map.get("timestamp")));
												} catch (Exception ignored) {
												}
											}
											for (Map.Entry<String, Object> entry : map.entrySet()) {
												String k = entry.getKey();
												if (!"message".equals(k) && !"level".equals(k) && !"timestamp".equals(k)) {
													attributes.put(k, entry.getValue());
												}
											}
										}
									} catch (Exception ignored) {
									}
								}

								logs.add(new LogEntryResponse(
										nanoStr,
										ts,
										streamService,
										serviceId,
										logLevel.toUpperCase(),
										logMsg,
										attributes
								));
							}
						}
					}
				}
			}
		} catch (Exception e) {
			log.warn("Failed to parse Loki response: {}", e.getMessage());
		}

		// Sort newest first per US-28
		logs.sort((a, b) -> b.timestamp().compareTo(a.timestamp()));

		if (logs.size() > limit) {
			return logs.subList(0, limit);
		}
		return logs;
	}

	public List<MetricDataPoint> parsePrometheusMatrix(String responseBody) {
		List<MetricDataPoint> points = new ArrayList<>();
		if (responseBody == null || responseBody.isBlank()) {
			return points;
		}

		try {
			JsonNode root = objectMapper.readTree(responseBody);
			JsonNode result = root.path("data").path("result");
			if (result.isArray()) {
				for (JsonNode series : result) {
					JsonNode values = series.path("values");
					if (values.isArray()) {
						for (JsonNode val : values) {
							if (val.isArray() && val.size() >= 2) {
								double timeSec = val.get(0).asDouble();
								double valNum = Double.parseDouble(val.get(1).asText());
								Instant ts = Instant.ofEpochMilli((long) (timeSec * 1000));
								points.add(new MetricDataPoint(ts, valNum));
							}
						}
					}
				}
			}
		} catch (Exception e) {
			log.warn("Failed to parse Prometheus matrix response: {}", e.getMessage());
		}
		return points;
	}

	private RestClient createLokiClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
		factory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));

		var builder = RestClient.builder()
				.requestFactory(factory)
				.baseUrl(properties.lokiUrl());

		if (properties.readToken() != null && !properties.readToken().isBlank()) {
			if (properties.lokiUser() != null && !properties.lokiUser().isBlank()) {
				String credentials = properties.lokiUser() + ":" + properties.readToken();
				String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
				builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth);
			} else {
				builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.readToken());
			}
		}
		return builder.build();
	}

	private RestClient createPrometheusClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
		factory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));

		var builder = RestClient.builder()
				.requestFactory(factory)
				.baseUrl(properties.prometheusUrl());

		if (properties.readToken() != null && !properties.readToken().isBlank()) {
			if (properties.prometheusUser() != null && !properties.prometheusUser().isBlank()) {
				String credentials = properties.prometheusUser() + ":" + properties.readToken();
				String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
				builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth);
			} else {
				builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.readToken());
			}
		}
		return builder.build();
	}
}
