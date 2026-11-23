package com.settle.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.settle.common.error.ServiceUnavailableException;
import com.settle.investigation.dto.DraftKind;
import com.settle.investigation.service.AiDraftClient.AiDraftRequest;
import com.settle.investigation.service.AiInvestigationClient.IncidentEvidenceContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class AiDraftClientTest {

	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicInteger delayMillis = new AtomicInteger(0);
	private final AtomicReference<String> requestBody = new AtomicReference<>();
	private final AtomicReference<String> responseBody = new AtomicReference<>("""
			{
				"aiGenerated": true,
				"kind": "SUMMARY",
				"text": "Summary for INC-1.",
				"postmortem": null
			}
			""");

	private HttpServer server;
	private String baseUrl;

	@BeforeEach
	void startStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		server.createContext("/draft", this::respond);
		server.start();
		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopStub() {
		server.stop(0);
	}

	@Test
	void postsDraftRequestAndParsesResponse() {
		var result = client(baseUrl, Duration.ofSeconds(2)).generateDraft(sampleRequest());

		assertThat(result.kind()).isEqualTo(DraftKind.SUMMARY);
		assertThat(result.text()).isEqualTo("Summary for INC-1.");
		assertThat(result.postmortem()).isNull();
		assertThat(requestBody.get()).contains("\"kind\":\"SUMMARY\"", "\"INC-1\"");
	}

	@Test
	void unsetUrlThrowsServiceUnavailable() {
		AiDraftClient disabled = client("", Duration.ofSeconds(2));

		assertThat(disabled.isEnabled()).isFalse();
		assertThatThrownBy(() -> disabled.generateDraft(sampleRequest()))
				.isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void nullUrlDisablesDrafting() {
		assertThat(client(null, Duration.ofSeconds(2)).isEnabled()).isFalse();
	}

	@Test
	void acceptsHttpsBaseUrlWithTrailingSlashAndWhitespace() {
		assertThat(client(" https://ai.example.test/ai/ ", Duration.ofSeconds(2)).isEnabled()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	void rejectsNonPositiveTimeout(long seconds) {
		assertThatThrownBy(() -> client(baseUrl, Duration.ofSeconds(seconds)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("AI draft timeout must be positive");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"ftp://localhost/", "http:/draft/", "http://user@localhost/",
			"http://localhost/?debug=true", "http://localhost/#fragment"
	})
	void rejectsInvalidBaseUrls(String url) {
		assertThatThrownBy(() -> client(url, Duration.ofSeconds(2)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("AI service URL must be an HTTP(S) base URL without credentials or query parameters");
	}

	@ParameterizedTest
	@ValueSource(strings = {"null", "not JSON"})
	void unusableResponseThrowsServiceUnavailable(String body) {
		responseBody.set(body);

		assertThatThrownBy(() -> client(baseUrl, Duration.ofSeconds(2)).generateDraft(sampleRequest()))
				.isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void non2xxThrowsServiceUnavailable() {
		status.set(503);

		assertThatThrownBy(() -> client(baseUrl, Duration.ofSeconds(2)).generateDraft(sampleRequest()))
				.isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void slowServiceTimesOutWithServiceUnavailable() {
		delayMillis.set(500);

		assertThatThrownBy(() -> client(baseUrl, Duration.ofMillis(100)).generateDraft(sampleRequest()))
				.isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void refusedConnectionThrowsServiceUnavailable() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
			closedPort = socket.getLocalPort();
		}

		assertThatThrownBy(() -> client("http://127.0.0.1:" + closedPort, Duration.ofSeconds(2))
				.generateDraft(sampleRequest()))
				.isInstanceOf(ServiceUnavailableException.class);
	}

	private AiDraftClient client(String url, Duration timeout) {
		return new AiDraftClient(url, timeout, JsonMapper.builder().build());
	}

	private AiDraftRequest sampleRequest() {
		return new AiDraftRequest(
				DraftKind.SUMMARY,
				new IncidentEvidenceContext(1L, "INC-1", "Gateway slow", "Timeout",
						"Payments", "SEV2", "OPEN", Instant.parse("2026-11-10T10:00:00Z")),
				List.of(),
				List.of(),
				List.of(),
				List.of()
		);
	}

	private void respond(HttpExchange exchange) throws IOException {
		requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		int delay = delayMillis.get();
		if (delay > 0) {
			try {
				Thread.sleep(delay);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status.get(), bytes.length);
		try (var out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
