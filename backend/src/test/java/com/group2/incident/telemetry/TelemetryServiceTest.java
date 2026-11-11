package com.group2.incident.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.telemetry.config.TelemetryProperties;
import com.group2.incident.telemetry.dto.LogEntryResponse;
import com.group2.incident.telemetry.dto.MetricDataPoint;
import com.group2.incident.telemetry.dto.ServiceMetricsResponse;
import com.group2.incident.telemetry.service.TelemetryService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests for TelemetryService satisfying TC-FR-26.1a, TC-FR-26.1c, TC-FR-26.2a, TC-FR-26.3a, TC-FR-27.1a.
 */
class TelemetryServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private HttpServer stubServer;
	private String stubBaseUrl;
	private final List<String> receivedAuthHeaders = new CopyOnWriteArrayList<>();
	private final AtomicReference<String> lastLokiQuery = new AtomicReference<>();
	private final AtomicReference<String> lastPromQuery = new AtomicReference<>();

	private ServiceRepository serviceRepository;
	private JsonMapper objectMapper;

	@BeforeEach
	void setUp() throws IOException {
		objectMapper = JsonMapper.builder().build();
		serviceRepository = mock(ServiceRepository.class);

		ServiceEntity mockService = mock(ServiceEntity.class);
		when(mockService.getId()).thenReturn(1L);
		when(mockService.getName()).thenReturn("checkout");
		when(serviceRepository.findById(1L)).thenReturn(Optional.of(mockService));
		when(serviceRepository.findByNameIgnoreCase("checkout")).thenReturn(Optional.of(mockService));

		stubServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

		stubServer.createContext("/loki/api/v1/query_range", exchange -> {
			String auth = exchange.getRequestHeaders().getFirst("Authorization");
			if (auth != null) {
				receivedAuthHeaders.add(auth);
			}
			lastLokiQuery.set(exchange.getRequestURI().getQuery());

			String jsonResponse = """
					{
					  "status": "success",
					  "data": {
					    "resultType": "streams",
					    "result": [
					      {
					        "stream": { "service": "checkout", "level": "WARN" },
					        "values": [
					          [ "1731240000000000000", "{\\"timestamp\\":\\"2026-11-10T12:00:00Z\\",\\"level\\":\\"WARN\\",\\"message\\":\\"Payment gateway timeout\\",\\"payment_id\\":\\"pay_123\\"}" ],
					          [ "1731239990000000000", "Plain text log entry without json" ]
					        ]
					      }
					    ]
					  }
					}
					""";
			sendResponse(exchange, 200, jsonResponse);
		});

		stubServer.createContext("/api/v1/query_range", exchange -> {
			String auth = exchange.getRequestHeaders().getFirst("Authorization");
			if (auth != null) {
				receivedAuthHeaders.add(auth);
			}
			lastPromQuery.set(exchange.getRequestURI().getQuery());

			String jsonResponse = """
					{
					  "status": "success",
					  "data": {
					    "resultType": "matrix",
					    "result": [
					      {
					        "metric": { "service": "checkout" },
					        "values": [
					          [ 1731240000.0, "42.5" ],
					          [ 1731240060.0, "47.0" ]
					        ]
					      }
					    ]
					  }
					}
					""";
			sendResponse(exchange, 200, jsonResponse);
		});

		stubServer.start();
		stubBaseUrl = "http://127.0.0.1:" + stubServer.getAddress().getPort();
	}

	@AfterEach
	void tearDown() {
		if (stubServer != null) {
			stubServer.stop(0);
		}
	}

	private void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
		byte[] bytes = responseText.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
		exchange.close();
	}

	private TelemetryProperties createProperties(String readToken) {
		return new TelemetryProperties(
				new TelemetryProperties.PrometheusConfig(stubBaseUrl, ""),
				new TelemetryProperties.LokiConfig(stubBaseUrl, ""),
				readToken,
				2
		);
	}

	// ── TC-FR-26.1a: LogQL query construction & escaping ────────────────────

	@Test
	@DisplayName("TC-FR-26.1a: Query construction across service, level, text, and escaping")
	void testBuildLogQuery_combinationsAndEscaping() {
		TelemetryService service = new TelemetryService(
				createProperties(""), serviceRepository, objectMapper, CLOCK);

		// Service only
		assertThat(service.buildLogQuery("checkout", null, null))
				.isEqualTo("{service=\"checkout\"}");

		// Level only
		assertThat(service.buildLogQuery(null, "ERROR", null))
				.isEqualTo("{level=\"ERROR\"}");

		// Service and level
		assertThat(service.buildLogQuery("checkout", "warn", null))
				.isEqualTo("{service=\"checkout\", level=\"WARN\"}");

		// Neither service nor level ("_all" or empty)
		assertThat(service.buildLogQuery("_all", "_all", null))
				.isEqualTo("{service=~\".+\"}");

		// Free text search with dangerous LogQL / injection characters: quotes, backslashes, braces, LogQL pipes
		String userText = "error \"timeout\" \\ fail |= bad {service=\"injected\"}";
		String query = service.buildLogQuery("checkout", "ERROR", userText);

		// User text must be placed in a literal filter |= "..." and escaped
		assertThat(query).startsWith("{service=\"checkout\", level=\"ERROR\"} |= \"");
		assertThat(query).contains("error \\\"timeout\\\" \\\\ fail |= bad {service=\\\"injected\\\"}");
		assertThat(query).endsWith("\"");
	}

	@Test
	@DisplayName("TC-FR-26.1a: Loki query execution and response parsing")
	void testQueryLogsAgainstStubbedLoki() {
		TelemetryProperties props = createProperties("dummy-token");
		TelemetryService service = new TelemetryService(props, serviceRepository, objectMapper, CLOCK);

		Instant from = NOW.minus(Duration.ofMinutes(15));
		Instant to = NOW;

		List<LogEntryResponse> logs = service.queryLogs(1L, null, "WARN", "timeout", from, to, 50);

		assertThat(logs).hasSize(2);

		// JSON line parsed
		LogEntryResponse jsonEntry = logs.get(0);
		assertThat(jsonEntry.serviceName()).isEqualTo("checkout");
		assertThat(jsonEntry.level()).isEqualTo("WARN");
		assertThat(jsonEntry.message()).isEqualTo("Payment gateway timeout");
		assertThat(jsonEntry.attributes()).containsEntry("payment_id", "pay_123");

		// Plain text line parsed
		LogEntryResponse plainEntry = logs.get(1);
		assertThat(plainEntry.message()).isEqualTo("Plain text log entry without json");

		// Check query sent to Loki
		String lokiQuery = lastLokiQuery.get();
		assertThat(lokiQuery).isNotNull();
		assertThat(lokiQuery).contains("query=");
		assertThat(lokiQuery).contains("direction=BACKWARD");
		assertThat(lokiQuery).contains("limit=50");
	}

	// ── TC-FR-26.1c: Read token is never logged ─────────────────────────────

	@Test
	@DisplayName("TC-FR-26.1c: Read token is never logged in any application log line")
	void testReadTokenIsNotLogged() {
		String sensitiveToken = "super-secret-read-token-xyz-98765";
		TelemetryProperties props = createProperties(sensitiveToken);
		TelemetryService service = new TelemetryService(props, serviceRepository, objectMapper, CLOCK);

		Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
		listAppender.start();
		rootLogger.addAppender(listAppender);

		try {
			service.queryLogs(null, "checkout", "INFO", "hello", NOW.minus(Duration.ofMinutes(5)), NOW, 10);
			service.queryMetrics(null, "checkout", NOW.minus(Duration.ofMinutes(5)), NOW, "60s");

			// Confirm stub received the auth header with the token
			assertThat(receivedAuthHeaders).isNotEmpty();
			assertThat(receivedAuthHeaders).allMatch(h -> h.contains(sensitiveToken));

			// Confirm token never appears in any logged line
			for (ILoggingEvent event : listAppender.list) {
				assertThat(event.getFormattedMessage())
						.as("Log message contains sensitive token")
						.doesNotContain(sensitiveToken);

				if (event.getArgumentArray() != null) {
					for (Object arg : event.getArgumentArray()) {
						if (arg != null) {
							assertThat(arg.toString())
									.as("Log argument contains sensitive token")
									.doesNotContain(sensitiveToken);
						}
					}
				}
			}
		} finally {
			rootLogger.detachAppender(listAppender);
			listAppender.stop();
		}
	}

	// ── TC-FR-26.2a: Time range cap at 24 hours ─────────────────────────────

	@Test
	@DisplayName("TC-FR-26.2a: Time range of exactly 24 hours is accepted; 24h + 1s rejected with 400")
	void testTimeRangeCap24Hours() {
		TelemetryService service = new TelemetryService(
				createProperties(""), serviceRepository, objectMapper, CLOCK);

		Instant to = NOW;
		Instant exact24hFrom = to.minus(Duration.ofHours(24));
		Instant over24hFrom = exact24hFrom.minusSeconds(1);

		// Exactly 24 hours: accepted
		service.validateTimeRange(exact24hFrom, to);

		// 24 hours + 1 second: throws FieldValidationException (mapped to 400 Bad Request)
		assertThatThrownBy(() -> service.validateTimeRange(over24hFrom, to))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("24 hours");

		// Inverted range: from > to
		assertThatThrownBy(() -> service.validateTimeRange(to, exact24hFrom))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("after");
	}

	// ── TC-FR-26.3a: Unreachable / slow Grafana returns 503 ─────────────────

	@Test
	@DisplayName("TC-FR-26.3a: Unreachable or slow Grafana returns 503 within timeout")
	void testGrafanaSlowOrUnavailableThrows503() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}

		TelemetryProperties brokenProps = new TelemetryProperties(
				new TelemetryProperties.PrometheusConfig("http://127.0.0.1:" + closedPort, ""),
				new TelemetryProperties.LokiConfig("http://127.0.0.1:" + closedPort, ""),
				"test-token",
				1
		);

		TelemetryService service = new TelemetryService(brokenProps, serviceRepository, objectMapper, CLOCK);

		Instant from = NOW.minus(Duration.ofMinutes(5));
		Instant to = NOW;

		assertThatThrownBy(() -> service.queryLogs(null, "checkout", null, null, from, to, 10))
				.isInstanceOf(ServiceUnavailableException.class)
				.hasMessageContaining("Log store is slow or unavailable");

		assertThatThrownBy(() -> service.queryMetrics(null, "checkout", from, to, "60s"))
				.isInstanceOf(ServiceUnavailableException.class)
				.hasMessageContaining("Metrics store is slow or unavailable");
	}

	// ── TC-FR-27.1a: PromQL query construction and series response ──────────

	@Test
	@DisplayName("TC-FR-27.1a: PromQL query construction and series response for each metric")
	void testPrometheusQueriesAndParsing() {
		TelemetryProperties props = createProperties("dummy-token");
		TelemetryService service = new TelemetryService(props, serviceRepository, objectMapper, CLOCK);

		// Check PromQL query templates for a service
		assertThat(service.buildRequestRateQuery("checkout"))
				.isEqualTo("sum(rate(demo_http_requests_total{service=\"checkout\"}[5m]))");

		assertThat(service.buildErrorRateQuery("checkout"))
				.isEqualTo("sum(rate(demo_http_requests_total{service=\"checkout\",status=~\"5..\"}[5m])) / sum(rate(demo_http_requests_total{service=\"checkout\"}[5m]))");

		assertThat(service.buildLatencyP50Query("checkout"))
				.isEqualTo("histogram_quantile(0.50, sum(rate(demo_http_request_duration_seconds_bucket{service=\"checkout\"}[5m])) by (le))");

		assertThat(service.buildLatencyP95Query("checkout"))
				.isEqualTo("histogram_quantile(0.95, sum(rate(demo_http_request_duration_seconds_bucket{service=\"checkout\"}[5m])) by (le))");

		// Check PromQL query templates for all services
		assertThat(service.buildRequestRateQuery(null))
				.isEqualTo("sum(rate(demo_http_requests_total[5m]))");

		// Execute against stubbed Prometheus
		Instant from = NOW.minus(Duration.ofMinutes(10));
		Instant to = NOW;

		ServiceMetricsResponse metrics = service.queryMetrics(null, "checkout", from, to, "60s");

		assertThat(metrics.serviceName()).isEqualTo("checkout");
		assertThat(metrics.requestRate()).hasSize(2);
		assertThat(metrics.errorRate()).hasSize(2);
		assertThat(metrics.latencyP50()).hasSize(2);
		assertThat(metrics.latencyP95()).hasSize(2);

		MetricDataPoint first = metrics.requestRate().get(0);
		assertThat(first.value()).isEqualTo(42.5);

		MetricDataPoint second = metrics.requestRate().get(1);
		assertThat(second.value()).isEqualTo(47.0);

		// Single metric query
		List<MetricDataPoint> singleSeries = service.queryMetric("requestRate", "checkout", from, to, "60s");
		assertThat(singleSeries).hasSize(2);
		assertThat(singleSeries.get(0).value()).isEqualTo(42.5);
	}
}
