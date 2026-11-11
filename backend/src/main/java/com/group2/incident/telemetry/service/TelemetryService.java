package com.group2.incident.telemetry.service;

import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.telemetry.config.TelemetryProperties;
import com.group2.incident.telemetry.dto.LogEntryResponse;
import com.group2.incident.telemetry.dto.MetricDataPoint;
import com.group2.incident.telemetry.dto.MetricSeriesResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Logs from Loki (FR-26) and metrics from Prometheus (FR-27), queried with the backend's own read token (D34).
 * The browser never sees the token or writes a query: each query is built here from a service of the caller's
 * org and a few checked parameters. Grafana being slow or down answers 503 within the timeout; nothing else in
 * the app depends on it.
 */
@Service
@Slf4j
public class TelemetryService {

	static final Duration MAX_RANGE = Duration.ofHours(24);

	static final Duration DEFAULT_RANGE = Duration.ofHours(1);

	static final Duration MIN_STEP = Duration.ofSeconds(15);

	static final Duration MAX_STEP = Duration.ofHours(1);

	static final int MAX_LIMIT = 1000;

	private static final Set<String> LEVELS = Set.of("DEBUG", "INFO", "WARN", "ERROR");

	private static final Set<String> MESSAGE_FIELDS = Set.of("message", "event", "level", "timestamp");

	private static final Pattern STEP = Pattern.compile("(\\d{1,5})([smh]?)");

	/** The four series a service's dashboard shows (FR-27.1), as PromQL over demo-shop's metric names. */
	public enum Metric {
		REQUEST_RATE("requestRate", "sum(rate(demo_http_requests_total{service=\"%1$s\"}[5m]))"),
		ERROR_RATE("errorRate",
				"sum(rate(demo_http_requests_total{service=\"%1$s\",status=~\"5..\"}[5m]))"
						+ " / sum(rate(demo_http_requests_total{service=\"%1$s\"}[5m]))"),
		LATENCY_P50("latencyP50",
				"histogram_quantile(0.50, sum(rate(demo_http_request_duration_seconds_bucket{service=\"%1$s\"}[5m])) by (le))"),
		LATENCY_P95("latencyP95",
				"histogram_quantile(0.95, sum(rate(demo_http_request_duration_seconds_bucket{service=\"%1$s\"}[5m])) by (le))");

		private final String apiName;

		private final String template;

		Metric(String apiName, String template) {
			this.apiName = apiName;
			this.template = template;
		}

		public String apiName() {
			return apiName;
		}

		String query(String serviceLabel) {
			return template.formatted(escape(serviceLabel));
		}

		static Metric parse(String value) {
			for (Metric metric : values()) {
				if (metric.apiName.equals(value)) {
					return metric;
				}
			}
			throw new FieldValidationException("metric", "must be requestRate, errorRate, latencyP50, or latencyP95");
		}
	}

	private final TelemetryProperties properties;

	private final ServiceRepository serviceRepository;

	private final JsonMapper jsonMapper;

	private final Clock clock;

	private final HttpClient httpClient;

	public TelemetryService(TelemetryProperties properties, ServiceRepository serviceRepository, JsonMapper jsonMapper,
			Clock clock) {
		this.properties = properties;
		this.serviceRepository = serviceRepository;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(timeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	/** Log lines of one service, newest first, filtered by level and by text matched literally (FR-26.1). */
	public List<LogEntryResponse> queryLogs(long serviceId, String level, String text, Instant from, Instant to,
			int limit) {
		ServiceEntity service = requireService(serviceId);
		Range range = range(from, to);
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new FieldValidationException("limit", "must be between 1 and " + MAX_LIMIT);
		}
		String normalizedLevel = level == null || level.isBlank() ? null : level.trim().toUpperCase(Locale.ROOT);
		if (normalizedLevel != null && !LEVELS.contains(normalizedLevel)) {
			throw new FieldValidationException("level", "must be DEBUG, INFO, WARN, or ERROR");
		}
		Map<String, String> params = new LinkedHashMap<>();
		params.put("query", logQuery(serviceLabel(service), normalizedLevel, text));
		params.put("start", String.valueOf(nanos(range.from())));
		params.put("end", String.valueOf(nanos(range.to())));
		params.put("limit", String.valueOf(limit));
		params.put("direction", "backward");
		JsonNode body = get(properties.lokiUrl(), properties.lokiUser(), "/loki/api/v1/query_range", params, "Logs");
		return parseLogs(body, service, limit);
	}

	/** One metric series of one service (FR-27.1). */
	public MetricSeriesResponse queryMetric(long serviceId, String metricName, Instant from, Instant to, String step) {
		ServiceEntity service = requireService(serviceId);
		Metric metric = Metric.parse(metricName);
		Range range = range(from, to);
		Duration stepDuration = step(step);
		Map<String, String> params = new LinkedHashMap<>();
		params.put("query", metric.query(serviceLabel(service)));
		params.put("start", String.valueOf(range.from().getEpochSecond()));
		params.put("end", String.valueOf(range.to().getEpochSecond()));
		params.put("step", String.valueOf(stepDuration.toSeconds()));
		JsonNode body = get(properties.prometheusUrl(), properties.prometheusUser(), prometheusPath(), params, "Metrics");
		return new MetricSeriesResponse(metric.apiName(), service.getId(), service.getName(), range.from(), range.to(),
				stepDuration.toSeconds(), parseMatrix(body));
	}

	// ---------- Queries, built here only ----------

	/** The {@code service} label demo-shop puts on its logs and metrics: the service name in lower case, words joined by hyphens. */
	static String serviceLabel(ServiceEntity service) {
		return service.getName().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
	}

	static String logQuery(String serviceLabel, String level, String text) {
		StringBuilder query = new StringBuilder("{service=\"").append(escape(serviceLabel)).append("\"}");
		if (level != null) {
			query.append(" | json | level=\"").append(escape(level)).append('"');
		}
		if (text != null && !text.isBlank()) {
			// A line filter compares text literally; it never becomes LogQL syntax (TC-FR-26.1a).
			query.append(" |= \"").append(escape(text.trim())).append('"');
		}
		return query.toString();
	}

	/** Escapes a value for a LogQL or PromQL double-quoted string. */
	static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	private String prometheusPath() {
		// Grafana Cloud's address ends in /api/prom; a plain Prometheus serves /api/v1 at its root.
		return properties.prometheusUrl().replaceAll("/+$", "").endsWith("/api/v1") ? "/query_range" : "/api/v1/query_range";
	}

	// ---------- Parameters ----------

	private ServiceEntity requireService(long serviceId) {
		// Filtered by org (D48): another org's service is not found, so its telemetry is never asked for.
		return serviceRepository.findById(serviceId)
				.orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));
	}

	private record Range(Instant from, Instant to) {
	}

	/** The last hour unless given; at most 24 hours (FR-26.2). */
	private Range range(Instant from, Instant to) {
		Instant end = to != null ? to : clock.instant();
		Instant start = from != null ? from : end.minus(DEFAULT_RANGE);
		if (end.isBefore(start)) {
			throw new FieldValidationException("to", "must be after from");
		}
		if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
			throw new FieldValidationException("from", "The time range can be at most 24 hours");
		}
		return new Range(start, end);
	}

	/** "60", "60s", "5m" or "1h", from 15 seconds to 1 hour: at most 5,760 points over 24 hours, under Prometheus's 11,000. */
	static Duration step(String value) {
		Matcher matcher = STEP.matcher(value == null || value.isBlank() ? "60s" : value.trim());
		if (!matcher.matches()) {
			throw new FieldValidationException("step", "must be seconds, or a number with s, m, or h, such as 60s or 5m");
		}
		long amount = Long.parseLong(matcher.group(1));
		Duration step = switch (matcher.group(2)) {
			case "m" -> Duration.ofMinutes(amount);
			case "h" -> Duration.ofHours(amount);
			default -> Duration.ofSeconds(amount);
		};
		if (step.compareTo(MIN_STEP) < 0 || step.compareTo(MAX_STEP) > 0) {
			throw new FieldValidationException("step", "must be between 15s and 1h");
		}
		return step;
	}

	private static long nanos(Instant instant) {
		return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
	}

	// ---------- Calling Grafana ----------

	private Duration timeout() {
		return Duration.ofSeconds(properties.timeoutSeconds());
	}

	/**
	 * One GET to Loki or Prometheus with the read token. The whole call, from connecting to the last byte, ends
	 * within the timeout. Failures name the store, never the token or the response.
	 */
	private JsonNode get(String baseUrl, String user, String path, Map<String, String> params, String store) {
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new ServiceUnavailableException(store + " are not set up on this server");
		}
		StringBuilder uri = new StringBuilder(baseUrl.replaceAll("/+$", "")).append(path).append('?');
		params.forEach((name, value) -> uri.append(name).append('=')
				.append(URLEncoder.encode(value, StandardCharsets.UTF_8)).append('&'));
		uri.setLength(uri.length() - 1);
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri.toString())).timeout(timeout()).GET();
		String auth = authorization(user);
		if (auth != null) {
			request.header("Authorization", auth);
		}
		try {
			HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				log.warn("{} store answered HTTP {}", store, response.statusCode());
				throw new ServiceUnavailableException(store + " are unavailable right now");
			}
			return jsonMapper.readTree(response.body());
		}
		catch (IOException | JacksonException e) {
			log.warn("{} store is slow or unreachable: {}", store, e.getClass().getSimpleName());
			throw new ServiceUnavailableException(store + " are slow or unavailable right now", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ServiceUnavailableException(store + " are slow or unavailable right now", e);
		}
	}

	private String authorization(String user) {
		String token = properties.readToken();
		if (token == null || token.isBlank()) {
			return null;
		}
		if (user != null && !user.isBlank()) {
			return "Basic " + Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
		}
		return "Bearer " + token;
	}

	// ---------- Reading the answers ----------

	private List<LogEntryResponse> parseLogs(JsonNode body, ServiceEntity service, int limit) {
		List<LogEntryResponse> logs = new ArrayList<>();
		for (JsonNode stream : body.path("data").path("result")) {
			JsonNode labels = stream.path("stream");
			for (JsonNode value : stream.path("values")) {
				if (value.size() < 2) {
					continue;
				}
				String nanos = value.get(0).asString();
				String line = value.get(1).asString();
				String level = labels.path("level").asString(labels.path("detected_level").asString("INFO"));
				String message = line;
				Map<String, Object> attributes = new LinkedHashMap<>();
				JsonNode json = readObject(line);
				if (json != null) {
					// demo-shop writes "event"; other services may write "message".
					message = json.path("message").asString(json.path("event").asString(line));
					level = json.path("level").asString(level);
					for (Map.Entry<String, JsonNode> field : json.properties()) {
						if (!MESSAGE_FIELDS.contains(field.getKey())) {
							attributes.put(field.getKey(), jsonMapper.convertValue(field.getValue(), Object.class));
						}
					}
				}
				logs.add(new LogEntryResponse(nanos + ":" + logs.size(), parseNanos(nanos), service.getId(),
						service.getName(), level.toUpperCase(Locale.ROOT), message, attributes));
			}
		}
		logs.sort(Comparator.comparing(LogEntryResponse::timestamp).reversed());
		return logs.size() > limit ? List.copyOf(logs.subList(0, limit)) : logs;
	}

	private JsonNode readObject(String line) {
		String trimmed = line.trim();
		if (!trimmed.startsWith("{")) {
			return null;
		}
		try {
			JsonNode node = jsonMapper.readTree(trimmed);
			return node.isObject() ? node : null;
		}
		catch (JacksonException e) {
			return null;
		}
	}

	private static Instant parseNanos(String nanos) {
		try {
			long value = Long.parseLong(nanos);
			return Instant.ofEpochSecond(Math.floorDiv(value, 1_000_000_000L), Math.floorMod(value, 1_000_000_000L));
		}
		catch (NumberFormatException e) {
			return Instant.EPOCH;
		}
	}

	/** The first series of a matrix answer. NaN and infinity (0/0 when there is no traffic) are left out. */
	private static List<MetricDataPoint> parseMatrix(JsonNode body) {
		List<MetricDataPoint> points = new ArrayList<>();
		JsonNode series = body.path("data").path("result").path(0);
		for (JsonNode value : series.path("values")) {
			if (value.size() < 2) {
				continue;
			}
			double number;
			try {
				number = Double.parseDouble(value.get(1).asString());
			}
			catch (NumberFormatException e) {
				continue;
			}
			if (!Double.isFinite(number)) {
				continue;
			}
			long millis = Math.round(value.get(0).asDouble() * 1000);
			points.add(new MetricDataPoint(Instant.ofEpochMilli(millis), number));
		}
		return points;
	}
}
