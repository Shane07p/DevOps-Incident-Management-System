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
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.telemetry.config.TelemetryProperties;
import com.group2.incident.telemetry.service.ActiveConnection.Naming;
import com.group2.incident.telemetry.dto.LogEntryResponse;
import com.group2.incident.telemetry.dto.MetricSeriesResponse;
import com.group2.incident.telemetry.service.ActiveConnection;
import com.group2.incident.telemetry.service.TelemetryConnectionService;
import com.group2.incident.telemetry.service.TelemetryHttp;
import com.group2.incident.telemetry.service.TelemetryService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** TelemetryService against a stub Grafana on loopback: the queries it builds, its limits, and its failures. */
class TelemetryServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

	private static final String TOKEN = "glc_test-read-token-123";

	private HttpServer stub;

	private String baseUrl;

	private final AtomicReference<Map<String, String>> lastQuery = new AtomicReference<>();

	private final AtomicReference<String> lastPath = new AtomicReference<>();

	private final AtomicReference<String> lastAuth = new AtomicReference<>();

	private final AtomicReference<String> lokiBody = new AtomicReference<>("{\"data\":{\"result\":[]}}");

	private final AtomicReference<String> promBody = new AtomicReference<>("{\"data\":{\"result\":[]}}");

	private final AtomicReference<Integer> status = new AtomicReference<>(200);

	private final ServiceRepository services = mock(ServiceRepository.class);

	@BeforeEach
	void startStub() throws IOException {
		stub = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		stub.createContext("/", exchange -> {
			lastPath.set(exchange.getRequestURI().getPath());
			lastQuery.set(params(exchange.getRequestURI().getRawQuery()));
			lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			String body = exchange.getRequestURI().getPath().contains("loki") ? lokiBody.get() : promBody.get();
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status.get(), bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		stub.start();
		baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort();
		service(1L, "Checkout");
		service(2L, "Order Fulfillment");
	}

	@AfterEach
	void stopStub() {
		stub.stop(0);
	}

	// ---------- TC-FR-26.1a: the query is built from the parameters only ----------

	@Test
	void theLogQueryNamesTheServiceTheLevelAndTheText() {
		telemetry().queryLogs(1L, "error", "timeout", null, null, 100);

		assertThat(lastPath.get()).isEqualTo("/loki/api/v1/query_range");
		assertThat(lastQuery.get().get("query")).isEqualTo("{service=\"checkout\"} | json | level=\"ERROR\" |= \"timeout\"");
		assertThat(lastQuery.get()).containsEntry("limit", "100").containsEntry("direction", "backward")
				.containsEntry("end", String.valueOf(NOW.getEpochSecond() * 1_000_000_000L))
				.containsEntry("start", String.valueOf(NOW.minus(Duration.ofHours(1)).getEpochSecond() * 1_000_000_000L));
	}

	@Test
	void textThatLooksLikeLogQlStaysText() {
		telemetry().queryLogs(1L, null, "\"} |= \"x\" or {job=~\".+\"", null, null, 10);

		assertThat(lastQuery.get().get("query"))
				.isEqualTo("{service=\"checkout\"} |= \"\\\"} |= \\\"x\\\" or {job=~\\\".+\\\"\"");
	}

	@Test
	void aServiceNameWithSpacesBecomesItsHyphenatedLabel() {
		telemetry().queryLogs(2L, null, null, null, null, 10);

		assertThat(lastQuery.get().get("query")).isEqualTo("{service=\"order-fulfillment\"}");
	}

	@Test
	void anotherOrgsOrAnUnknownServiceIsNotFoundAndNothingIsSent() {
		assertThatThrownBy(() -> telemetry().queryLogs(99L, null, null, null, null, 10)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> telemetry().queryMetric(99L, "requestRate", null, null, "60s"))
				.isInstanceOf(NotFoundException.class);
		assertThat(lastPath.get()).isNull();
	}

	@ParameterizedTest
	@CsvSource({ "level, TRACE, 100", "limit, , 0", "limit, , 1001" })
	void anUnknownLevelOrALimitOutsideOneToAThousandIsRefused(String field, String level, int limit) {
		assertThatThrownBy(() -> telemetry().queryLogs(1L, level, null, null, null, limit))
				.isInstanceOf(FieldValidationException.class)
				.extracting("field").isEqualTo(field);
	}

	// ---------- TC-FR-26.2a: 24 hours is the cap ----------

	@Test
	void exactlyTwentyFourHoursIsAcceptedAndOneSecondMoreIsRefused() {
		telemetry().queryLogs(1L, null, null, NOW.minus(Duration.ofHours(24)), NOW, 10);
		assertThat(lastPath.get()).isNotNull();

		assertThatThrownBy(() -> telemetry().queryLogs(1L, null, null, NOW.minus(Duration.ofHours(24)).minusSeconds(1), NOW, 10))
				.isInstanceOf(FieldValidationException.class);
		assertThatThrownBy(() -> telemetry().queryMetric(1L, "errorRate", NOW, NOW.minusSeconds(1), "60s"))
				.isInstanceOf(FieldValidationException.class);
	}

	// ---------- TC-FR-26.1c: the token goes in the header and nowhere else ----------

	@Test
	void theReadTokenIsSentAsAHeaderAndNeverLogged() {
		Logger logger = (Logger) LoggerFactory.getLogger(TelemetryService.class);
		Logger httpLogger = (Logger) LoggerFactory.getLogger(TelemetryHttp.class);
		ListAppender<ILoggingEvent> captured = new ListAppender<>();
		captured.start();
		logger.addAppender(captured);
		httpLogger.addAppender(captured);
		try {
			telemetry().queryLogs(1L, null, null, null, null, 10);
			status.set(500);
			assertThatThrownBy(() -> telemetry().queryLogs(1L, null, null, null, null, 10))
					.isInstanceOf(ServiceUnavailableException.class);
		}
		finally {
			logger.detachAppender(captured);
			httpLogger.detachAppender(captured);
		}

		assertThat(lastAuth.get())
				.isEqualTo("Basic " + Base64.getEncoder().encodeToString(("123456:" + TOKEN).getBytes(StandardCharsets.UTF_8)));
		assertThat(captured.list).isNotEmpty();
		assertThat(captured.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(TOKEN));
		assertThat(lastQuery.get().values()).noneMatch(value -> value.contains(TOKEN));
	}

	// ---------- TC-FR-26.3a: slow or down gives 503 within the timeout ----------

	@Test
	void aGrafanaThatNeverAnswersGives503WithinTheTimeout() throws IOException {
		HttpServer silent = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		silent.createContext("/", exchange -> {
			try {
				Thread.sleep(10_000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		silent.setExecutor(Executors.newCachedThreadPool());
		silent.start();
		try {
			String url = "http://127.0.0.1:" + silent.getAddress().getPort();
			long started = System.nanoTime();
			assertThatThrownBy(() -> telemetry(url, 1).queryLogs(1L, null, null, null, null, 10))
					.isInstanceOf(ServiceUnavailableException.class)
					.hasMessage("Logs are slow or unavailable right now");
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
		}
		finally {
			silent.stop(0);
		}
	}

	@Test
	void anErrorAnswerOrNoAddressGives503() {
		status.set(502);
		assertThatThrownBy(() -> telemetry().queryMetric(1L, "requestRate", null, null, "60s"))
				.isInstanceOf(ServiceUnavailableException.class);
	}

	// ---------- D49: only the org's own connection, with its own names ----------

	@Test
	void withNoConnectionNothingIsSentAndTheAnswerSaysSo() {
		TelemetryConnectionService none = mock(TelemetryConnectionService.class);
		when(none.current()).thenThrow(new ServiceUnavailableException("Telemetry isn't connected for your organization"));
		TelemetryService telemetry = new TelemetryService(none, services, http(5), JsonMapper.builder().build(),
				Clock.fixed(NOW, ZoneOffset.UTC));

		assertThatThrownBy(() -> telemetry.queryLogs(1L, null, null, null, null, 10))
				.isInstanceOf(ServiceUnavailableException.class)
				.hasMessage("Telemetry isn't connected for your organization");
		assertThat(lastPath.get()).isNull();
	}

	@Test
	void aStoreTheOrgLeftOutIs503AndTheOtherStillWorks() {
		TelemetryService logsOnly = telemetry(new ActiveConnection(null, null, URI.create(baseUrl), "123456", TOKEN,
				DEMO_NAMING), 5);

		assertThatThrownBy(() -> logsOnly.queryMetric(1L, "requestRate", null, null, "60s"))
				.isInstanceOf(ServiceUnavailableException.class)
				.hasMessage("Metrics aren't connected for your organization");
		assertThat(lastPath.get()).isNull();
		logsOnly.queryLogs(1L, null, null, null, null, 10);
		assertThat(lastPath.get()).isEqualTo("/loki/api/v1/query_range");
	}

	@Test
	void theOrgsOwnNamesAndTheServicesOwnLabelGoIntoTheQueries() {
		TelemetryService telemetry = telemetry(new ActiveConnection(URI.create(baseUrl), null, URI.create(baseUrl), null,
				TOKEN, new Naming("app", "http_requests_total", "code", "http_request_duration_seconds")), 5);
		ServiceEntity labelled = mock(ServiceEntity.class);
		when(labelled.getId()).thenReturn(3L);
		when(labelled.getName()).thenReturn("Payments");
		when(labelled.telemetryLabel()).thenReturn("payments-api");
		when(services.findById(3L)).thenReturn(Optional.of(labelled));

		telemetry.queryMetric(3L, "errorRate", null, null, "60s");
		assertThat(lastPath.get()).isEqualTo("/api/v1/query_range");
		assertThat(lastQuery.get().get("query")).isEqualTo(
				"sum(rate(http_requests_total{app=\"payments-api\",code=~\"5..\"}[5m])) / sum(rate(http_requests_total{app=\"payments-api\"}[5m]))");
		telemetry.queryLogs(3L, null, null, null, null, 10);
		assertThat(lastQuery.get().get("query")).isEqualTo("{app=\"payments-api\"}");
		// No user: the token goes as a bearer token.
		assertThat(lastAuth.get()).isEqualTo("Bearer " + TOKEN);
	}

	// ---------- Reading Loki's answer ----------

	@Test
	void logLinesComeBackNewestFirstWithDemoShopsEventAsTheMessage() {
		lokiBody.set("""
				{"data":{"result":[
				  {"stream":{"service":"checkout"},"values":[
				    ["1762776000000000000","{\\"timestamp\\":\\"2026-11-10T12:00:00Z\\",\\"level\\":\\"ERROR\\",\\"service\\":\\"checkout\\",\\"event\\":\\"checkout.failed\\",\\"request_id\\":\\"req-1\\"}"],
				    ["1762775940000000000","plain text line"]
				  ]},
				  {"stream":{"service":"checkout","detected_level":"warn"},"values":[
				    ["1762775970000000000","pool is almost full"]
				  ]}
				]}}""");

		List<LogEntryResponse> logs = telemetry().queryLogs(1L, null, null, null, null, 2);

		assertThat(logs).extracting(LogEntryResponse::message).containsExactly("checkout.failed", "pool is almost full");
		assertThat(logs.get(0).level()).isEqualTo("ERROR");
		assertThat(logs.get(0).attributes()).containsEntry("request_id", "req-1").containsEntry("service", "checkout")
				.doesNotContainKeys("event", "level", "timestamp");
		assertThat(logs.get(0).serviceId()).isEqualTo(1L);
		assertThat(logs.get(0).serviceName()).isEqualTo("Checkout");
		assertThat(logs.get(1).level()).isEqualTo("WARN");
	}

	// ---------- TC-FR-27.1a: each metric's query, and its series ----------

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"requestRate|sum(rate(demo_http_requests_total{service=\"checkout\"}[5m]))",
			"errorRate|sum(rate(demo_http_requests_total{service=\"checkout\",status=~\"5..\"}[5m])) / sum(rate(demo_http_requests_total{service=\"checkout\"}[5m]))",
			"latencyP50|histogram_quantile(0.50, sum(rate(demo_http_request_duration_seconds_bucket{service=\"checkout\"}[5m])) by (le))",
			"latencyP95|histogram_quantile(0.95, sum(rate(demo_http_request_duration_seconds_bucket{service=\"checkout\"}[5m])) by (le))" })
	void eachMetricHasItsOwnQuery(String metric, String query) {
		MetricSeriesResponse series = telemetry().queryMetric(1L, metric, null, null, "5m");

		assertThat(lastPath.get()).isEqualTo("/api/prom/api/v1/query_range");
		assertThat(lastQuery.get()).containsEntry("query", query).containsEntry("step", "300")
				.containsEntry("end", String.valueOf(NOW.getEpochSecond()));
		assertThat(series.metric()).isEqualTo(metric);
		assertThat(series.stepSeconds()).isEqualTo(300);
	}

	@Test
	void pointsWithNoValueAreLeftOut() {
		promBody.set("""
				{"data":{"result":[{"metric":{},"values":[
				  [1762776000,"0.25"],[1762776060,"NaN"],[1762776120,"+Inf"],[1762776180,"0.5"]
				]}]}}""");

		MetricSeriesResponse series = telemetry().queryMetric(1L, "errorRate", null, null, "60");

		assertThat(series.points()).extracting(p -> p.value()).containsExactly(0.25, 0.5);
		assertThat(series.points().get(0).timestamp()).isEqualTo(Instant.ofEpochSecond(1762776000));
	}

	@ParameterizedTest
	@CsvSource({ "requestRate, 14s", "requestRate, 2h", "requestRate, often", "throughput, 60s" })
	void aStepOutsideFifteenSecondsToAnHourOrAnUnknownMetricIsRefused(String metric, String step) {
		assertThatThrownBy(() -> telemetry().queryMetric(1L, metric, null, null, step))
				.isInstanceOf(FieldValidationException.class);
		assertThat(lastPath.get()).isNull();
	}

	// ---------- helpers ----------

	private TelemetryService telemetry() {
		return telemetry(baseUrl, 5);
	}

	/** Demo-shop's names, which the tests above were written against. */
	private static final Naming DEMO_NAMING = new Naming("service", "demo_http_requests_total", "status",
			"demo_http_request_duration_seconds");

	private TelemetryService telemetry(String url, int timeoutSeconds) {
		return telemetry(new ActiveConnection(URI.create(url + "/api/prom"), "123456", URI.create(url), "123456", TOKEN,
				DEMO_NAMING), timeoutSeconds);
	}

	private TelemetryService telemetry(ActiveConnection connection, int timeoutSeconds) {
		TelemetryConnectionService connections = mock(TelemetryConnectionService.class);
		when(connections.current()).thenReturn(connection);
		return new TelemetryService(connections, services, http(timeoutSeconds), JsonMapper.builder().build(),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	/** The stub listens on 127.0.0.1 over http, so these tests allow private targets; TelemetryHttpTest covers the guard. */
	private static TelemetryHttp http(int timeoutSeconds) {
		return new TelemetryHttp(new TelemetryProperties("", true, timeoutSeconds));
	}

	private void service(long id, String name) {
		ServiceEntity service = mock(ServiceEntity.class);
		when(service.getId()).thenReturn(id);
		when(service.getName()).thenReturn(name);
		when(service.telemetryLabel()).thenReturn(ServiceEntity.defaultTelemetryLabel(name));
		when(services.findById(id)).thenReturn(Optional.of(service));
	}

	private static Map<String, String> params(String rawQuery) {
		Map<String, String> params = new LinkedHashMap<>();
		if (rawQuery == null) {
			return params;
		}
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return params;
	}
}
