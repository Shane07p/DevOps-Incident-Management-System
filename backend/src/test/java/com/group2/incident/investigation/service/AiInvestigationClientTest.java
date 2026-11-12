package com.group2.incident.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.investigation.service.AiInvestigationClient.AiInvestigationRequest;
import com.group2.incident.investigation.service.AiInvestigationClient.EvidenceBundle;
import com.group2.incident.investigation.service.AiInvestigationClient.IncidentEvidenceContext;
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
import tools.jackson.databind.json.JsonMapper;

class AiInvestigationClientTest {

	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicInteger delayMillis = new AtomicInteger(0);
	private final AtomicReference<String> requestBody = new AtomicReference<>();
	private final AtomicReference<String> responseBody = new AtomicReference<>("""
			{
				"aiGenerated": true,
				"status": "completed",
				"summary": "Latency spike caused by bad deploy",
				"hypotheses": [
					{
						"hypothesis": "Faulty deploy v1.0.1-broken",
						"confidence": 0.92,
						"evidenceRefs": ["ev-deploy-0"],
						"reasoning": "Deployed 5 min before outage"
					}
				],
				"evidence": [
					{
						"id": "ev-deploy-0",
						"sourceTool": "query_deploy_events",
						"evidenceType": "deploy_event",
						"content": "Deploy v1.0.1-broken"
					}
				],
				"suggestedActions": ["Rollback to v1.0.0"],
				"stepsTaken": 2,
				"tokensUsed": 250,
				"durationMs": 42.5
			}
			""");

	private HttpServer server;
	private String baseUrl;

	@BeforeEach
	void startStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		server.createContext("/investigate", this::respond);
		server.start();
		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopStub() {
		server.stop(0);
	}

	@Test
	void postsBundleAndParsesResult() {
		var result = client(baseUrl, Duration.ofSeconds(2)).investigate(sampleRequest());

		assertThat(result.status()).isEqualTo("completed");
		assertThat(result.summary()).isEqualTo("Latency spike caused by bad deploy");
		assertThat(result.hypotheses()).hasSize(1);
		assertThat(result.suggestedActions()).containsExactly("Rollback to v1.0.0");
		assertThat(requestBody.get()).contains("\"evidenceBundle\"", "\"payments\"");
	}

	@Test
	void unsetUrlThrowsServiceUnavailable() {
		AiInvestigationClient disabled = client("", Duration.ofSeconds(2));

		assertThat(disabled.isEnabled()).isFalse();
		assertThatThrownBy(() -> disabled.investigate(sampleRequest()))
				.isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void non2xxThrowsServiceUnavailable() {
		status.set(503);

		assertThatThrownBy(() -> client(baseUrl, Duration.ofSeconds(2)).investigate(sampleRequest()))
				.isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void providerErrorStatusThrowsServiceUnavailable() {
		responseBody.set("""
				{"aiGenerated":true,"status":"provider_error","summary":"Ollama down","hypotheses":[],"evidence":[],"suggestedActions":[],"stepsTaken":0,"tokensUsed":0,"durationMs":1.0}
				""");

		assertThatThrownBy(() -> client(baseUrl, Duration.ofSeconds(2)).investigate(sampleRequest()))
				.isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void slowServiceTimesOutWithServiceUnavailable() {
		delayMillis.set(500);

		assertThatThrownBy(() -> client(baseUrl, Duration.ofMillis(100)).investigate(sampleRequest()))
				.isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void refusedConnectionThrowsServiceUnavailable() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
			closedPort = socket.getLocalPort();
		}

		assertThatThrownBy(() -> client("http://127.0.0.1:" + closedPort, Duration.ofSeconds(2))
				.investigate(sampleRequest()))
				.isInstanceOf(ServiceUnavailableException.class);
	}

	private AiInvestigationClient client(String url, Duration timeout) {
		return new AiInvestigationClient(url, timeout, JsonMapper.builder().build());
	}

	private AiInvestigationRequest sampleRequest() {
		return new AiInvestigationRequest(
				"payments",
				"2026-11-10T10:00:00Z",
				null,
				"Gateway timeout",
				new EvidenceBundle(
						new IncidentEvidenceContext(1L, "INC-1", "Gateway slow", "Gateway timeout",
								"Payments", "SEV2", "OPEN", Instant.parse("2026-11-10T10:00:00Z")),
						List.of(), List.of(), List.of(), List.of()
				)
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
