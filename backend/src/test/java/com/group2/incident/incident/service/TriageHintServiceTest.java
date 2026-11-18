package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.incident.dto.TriageHintRequest;
import com.group2.incident.incident.dto.TriageHintResponse;
import com.group2.incident.incident.dto.TriageHintServiceDto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TriageHintServiceTest {

	private final AtomicInteger status = new AtomicInteger(200);

	private final AtomicInteger delayMillis = new AtomicInteger();

	private final AtomicReference<String> requestBody = new AtomicReference<>();

	private final AtomicReference<String> responseBody = new AtomicReference<>(
			"{\"aiGenerated\":true,\"severity\":\"SEV2\",\"service\":{\"id\":1,\"name\":\"Payments\"},\"reason\":\"High urgency\"}"
	);

	private HttpServer server;

	private String baseUrl;

	@BeforeEach
	void startStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		server.createContext("/triage", this::respond);
		server.start();
		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopStub() {
		server.stop(0);
	}

	@Test
	@DisplayName("Returns empty optional when service URL is not configured")
	void returnsEmptyWhenDisabled() {
		var service = new TriageHintService("", Duration.ofSeconds(2), JsonMapper.builder().build());
		assertThat(service.isEnabled()).isFalse();
		assertThat(service.fetchHint(new TriageHintRequest("Title", "Desc"), List.of())).isEmpty();
	}

	@Test
	@DisplayName("Successfully parses valid triage hint response with Severity enum and valid service")
	void parsesValidResponse() {
		var service = new TriageHintService(baseUrl, Duration.ofSeconds(2), JsonMapper.builder().build());
		assertThat(service.isEnabled()).isTrue();

		var orgServices = List.of(new TriageHintServiceDto(1L, "Payments"));
		var result = service.fetchHint(new TriageHintRequest("Payment timeout", "504 errors on checkout"), List.of(), orgServices);

		assertThat(result).isPresent();
		TriageHintResponse hint = result.orElseThrow();
		assertThat(hint.severity()).isEqualTo(Severity.SEV2);
		assertThat(hint.service()).isNotNull();
		assertThat(hint.service().id()).isEqualTo(1L);
		assertThat(hint.service().name()).isEqualTo("Payments");
		assertThat(hint.reason()).isEqualTo("High urgency");
		assertThat(requestBody.get()).contains("\"title\":\"Payment timeout\"", "\"description\":\"504 errors on checkout\"");
	}

	@Test
	@DisplayName("Sets suggested service to null when suggested service ID is not in org services (Point 2)")
	void rejectsForeignServiceSuggestion() {
		var service = new TriageHintService(baseUrl, Duration.ofSeconds(2), JsonMapper.builder().build());
		// AI response suggests service id 1, but org only has service id 99
		var orgServices = List.of(new TriageHintServiceDto(99L, "OtherService"));
		var result = service.fetchHint(new TriageHintRequest("Payment timeout", "504 errors"), List.of(), orgServices);

		assertThat(result).isPresent();
		assertThat(result.orElseThrow().severity()).isEqualTo(Severity.SEV2);
		assertThat(result.orElseThrow().service()).isNull();
	}

	@Test
	@DisplayName("Returns empty optional when AI returns an invalid severity string (Point 4)")
	void returnsEmptyOnInvalidSeverityString() {
		responseBody.set("{\"aiGenerated\":true,\"severity\":\"INVALID_SEV\",\"service\":null,\"reason\":\"Bad severity\"}");
		var service = new TriageHintService(baseUrl, Duration.ofSeconds(2), JsonMapper.builder().build());

		var result = service.fetchHint(new TriageHintRequest("Some incident", "Some detail"), List.of());

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("Handles null service in AI response")
	void handlesNullService() {
		responseBody.set("{\"aiGenerated\":true,\"severity\":\"SEV3\",\"service\":null,\"reason\":\"Moderate impact\"}");
		var service = new TriageHintService(baseUrl, Duration.ofSeconds(2), JsonMapper.builder().build());

		var result = service.fetchHint(new TriageHintRequest("Minor issue", "Some detail"), List.of());

		assertThat(result).isPresent();
		assertThat(result.orElseThrow().severity()).isEqualTo(Severity.SEV3);
		assertThat(result.orElseThrow().service()).isNull();
	}

	@Test
	@DisplayName("Returns empty optional on HTTP error status")
	void returnsEmptyOnHttpError() {
		status.set(503);
		var service = new TriageHintService(baseUrl, Duration.ofSeconds(2), JsonMapper.builder().build());

		var result = service.fetchHint(new TriageHintRequest("Title", "Desc"), List.of());

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("Returns empty optional on unreadable response")
	void returnsEmptyOnMalformedJson() {
		responseBody.set("not-json");
		var service = new TriageHintService(baseUrl, Duration.ofSeconds(2), JsonMapper.builder().build());

		var result = service.fetchHint(new TriageHintRequest("Title", "Desc"), List.of());

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("Returns empty optional on timeout")
	void returnsEmptyOnTimeout() {
		delayMillis.set(300);
		var service = new TriageHintService(baseUrl, Duration.ofMillis(50), JsonMapper.builder().build());

		var result = service.fetchHint(new TriageHintRequest("Title", "Desc"), List.of());

		assertThat(result).isEmpty();
	}

	private void respond(HttpExchange exchange) throws IOException {
		requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		int sleep = delayMillis.get();
		if (sleep > 0) {
			try {
				Thread.sleep(sleep);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status.get(), bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
