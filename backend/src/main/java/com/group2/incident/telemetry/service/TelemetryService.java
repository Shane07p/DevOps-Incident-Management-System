package com.group2.incident.telemetry.service;

import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.telemetry.dto.LogEntryResponse;
import com.group2.incident.telemetry.dto.MetricDataPoint;
import com.group2.incident.telemetry.dto.MetricSeriesResponse;
import com.group2.incident.telemetry.service.ActiveConnection.Naming;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Logs from Loki (FR-26) and metrics from Prometheus (FR-27), read from the caller's org's own Grafana (D49,
 * #260): its connection, its token, its naming. There is no server-wide stack, so an org without a connection
 * gets 503 and never another org's data. The browser never sees the token or writes a query: each query is
 * built here from a service of the caller's org and a few checked parameters. Grafana being slow or down
 * answers 503 within the timeout; nothing else in the app depends on it.
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

	/**
	 * The four series a service's dashboard shows (FR-27.1), as PromQL over the org's own names: {@code {requests}}
	 * is its request counter, {@code {duration}} its duration histogram, {@code {label}} its service label and
	 * {@code {status}} its status label. The names were checked against Prometheus's rules when saved; the
	 * service's value is escaped.
	 */
	public enum Metric {
		REQUEST_RATE("requestRate", "sum(rate({requests}{{label}=\"{value}\"}[5m]))"),
		ERROR_RATE("errorRate",
				"sum(rate({requests}{{label}=\"{value}\",{status}=~\"5..\"}[5m]))"
						+ " / sum(rate({requests}{{label}=\"{value}\"}[5m]))"),
		LATENCY_P50("latencyP50",
				"histogram_quantile(0.50, sum(rate({duration}_bucket{{label}=\"{value}\"}[5m])) by (le))"),
		LATENCY_P95("latencyP95",
				"histogram_quantile(0.95, sum(rate({duration}_bucket{{label}=\"{value}\"}[5m])) by (le))");

		private final String apiName;

		private final String template;

		Metric(String apiName, String template) {
			this.apiName = apiName;
			this.template = template;
		}

		public String apiName() {
			return apiName;
		}

		String query(Naming naming, String serviceValue) {
			return template.replace("{requests}", naming.requestsMetric())
					.replace("{duration}", naming.durationMetric())
					.replace("{label}", naming.serviceLabel())
					.replace("{status}", naming.statusLabel())
					.replace("{value}", escape(serviceValue));
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

	private final TelemetryConnectionService connections;

	private final ServiceRepository serviceRepository;

	private final TelemetryHttp http;

	private final JsonMapper jsonMapper;

	private final Clock clock;

	public TelemetryService(TelemetryConnectionService connections, ServiceRepository serviceRepository,
			TelemetryHttp http, JsonMapper jsonMapper, Clock clock) {
		this.connections = connections;
		this.serviceRepository = serviceRepository;
		this.http = http;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
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
		ActiveConnection connection = connections.current();
		if (connection.loki() == null) {
			throw new ServiceUnavailableException("Logs aren't connected for your organization");
		}
		Map<String, String> params = new LinkedHashMap<>();
		params.put("query", logQuery(connection.naming().serviceLabel(), service.telemetryLabel(), normalizedLevel, text));
		params.put("start", String.valueOf(nanos(range.from())));
		params.put("end", String.valueOf(nanos(range.to())));
		params.put("limit", String.valueOf(limit));
		params.put("direction", "backward");
		JsonNode body = read(http.get(connection.loki(), "/loki/api/v1/query_range", params,
				connection.authorization(connection.lokiUser()), "Logs"), "Logs");
		return parseLogs(body, service, limit);
	}

	/** One metric series of one service (FR-27.1). */
	public MetricSeriesResponse queryMetric(long serviceId, String metricName, Instant from, Instant to, String step) {
		ServiceEntity service = requireService(serviceId);
		Metric metric = Metric.parse(metricName);
		Range range = range(from, to);
		Duration stepDuration = step(step);
		ActiveConnection connection = connections.current();
		if (connection.prometheus() == null) {
			throw new ServiceUnavailableException("Metrics aren't connected for your organization");
		}
		Map<String, String> params = new LinkedHashMap<>();
		params.put("query", metric.query(connection.naming(), service.telemetryLabel()));
		params.put("start", String.valueOf(range.from().getEpochSecond()));
		params.put("end", String.valueOf(range.to().getEpochSecond()));
		params.put("step", String.valueOf(stepDuration.toSeconds()));
		JsonNode body = read(http.get(connection.prometheus(),
				TelemetryConnectionService.prometheusPath(connection.prometheus(), "/query_range"), params,
				connection.authorization(connection.prometheusUser()), "Metrics"), "Metrics");
		return new MetricSeriesResponse(metric.apiName(), service.getId(), service.getName(), range.from(), range.to(),
				stepDuration.toSeconds(), parseMatrix(body));
	}

	// ---------- Queries, built here only ----------

	/** {@code labelName} was checked against Prometheus's label rules when saved; the value is escaped. */
	static String logQuery(String labelName, String serviceValue, String level, String text) {
		StringBuilder query = new StringBuilder("{").append(labelName).append("=\"").append(escape(serviceValue))
				.append("\"}");
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

	// ---------- Reading the answers ----------

	private JsonNode read(String body, String store) {
		try {
			return jsonMapper.readTree(body);
		}
		catch (JacksonException e) {
			log.warn("{} store answered with something that is not JSON", store);
			throw new ServiceUnavailableException(store + " are slow or unavailable right now", e);
		}
	}

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
