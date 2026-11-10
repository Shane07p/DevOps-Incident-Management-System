package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AiEmbeddingClientTest {

	private final AtomicInteger status = new AtomicInteger(200);

	private final AtomicInteger delayMillis = new AtomicInteger();

	private final AtomicReference<String> requestBody = new AtomicReference<>();

	private final AtomicReference<String> responseBody = new AtomicReference<>(vectorResponse(768));

	private HttpServer server;

	private String baseUrl;

	@BeforeEach
	void startStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		server.createContext("/embed", this::respond);
		server.start();
		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopStub() {
		server.stop(0);
	}

	@Test
	@DisplayName("Sends document text to POST /embed and accepts a 768-value vector")
	void postsDocumentAndAccepts768DimensionVector() {
		var result = client(baseUrl, Duration.ofSeconds(2)).embedDocument("Title: API outage");

		assertThat(result).isPresent();
		assertThat(result.orElseThrow()).hasSize(768).containsOnly(0.25);
		assertThat(requestBody.get()).contains("\"text\":\"Title: API outage\"", "\"kind\":\"document\"");
	}

	@Test
	@DisplayName("An unset AI URL disables embedding without making a request")
	void unsetUrlDisablesEmbedding() {
		AiEmbeddingClient client = client("", Duration.ofMillis(100));

		assertThat(client.isEnabled()).isFalse();
		assertThat(client.embedDocument("Title: API outage")).isEmpty();
		assertThat(requestBody.get()).isNull();
	}

	@Test
	@DisplayName("An AI service error response leaves embedding for retry")
	void serviceErrorLeavesEmbeddingForRetry() {
		status.set(503);

		assertThat(client(baseUrl, Duration.ofSeconds(2)).embedDocument("Title: API outage")).isEmpty();
	}

	@Test
	@DisplayName("An invalid vector dimension is rejected")
	void rejectsInvalidVectorDimension() {
		responseBody.set(vectorResponse(767));

		assertThat(client(baseUrl, Duration.ofSeconds(2)).embedDocument("Title: API outage")).isEmpty();
	}

	@Test
	@DisplayName("A vector value outside PostgreSQL float range is rejected")
	void rejectsVectorOutsideFloatRange() {
		responseBody.set(vectorResponse(768).replace("0.25", "1e100"));

		assertThat(client(baseUrl, Duration.ofSeconds(2)).embedDocument("Title: API outage")).isEmpty();
	}

	@Test
	@DisplayName("A slow AI service times out within the configured request deadline")
	void slowServiceTimesOut() {
		delayMillis.set(500);
		long started = System.nanoTime();

		assertThat(client(baseUrl, Duration.ofMillis(100)).embedDocument("Title: API outage")).isEmpty();

		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	@DisplayName("A refused connection leaves embedding for retry")
	void refusedConnectionLeavesEmbeddingForRetry() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
			closedPort = socket.getLocalPort();
		}

		assertThat(client("http://127.0.0.1:" + closedPort, Duration.ofSeconds(2))
				.embedDocument("Title: API outage")).isEmpty();
	}

	private AiEmbeddingClient client(String url, Duration timeout) {
		return new AiEmbeddingClient(url, timeout, JsonMapper.builder().build());
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
		answer(exchange, status.get(), responseBody.get());
	}

	private static void answer(HttpExchange exchange, int status, String body) throws IOException {
		byte[] response = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, response.length);
		try (var output = exchange.getResponseBody()) {
			output.write(response);
		}
	}

	private static String vectorResponse(int dimensions) {
		return "{\"embedding\":[" + "0.25,".repeat(dimensions - 1) + "0.25],\"model\":\"nomic-embed-text\"}";
	}
}
