package com.group2.incident.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Real HTTP against a local stub; private targets are allowed here only because the stub is on localhost. */
class HttpProbeTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);

	private final AtomicInteger requests = new AtomicInteger();

	private HttpServer server;

	private String base;

	@BeforeEach
	void startStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/ok", exchange -> answer(exchange, 200));
		server.createContext("/broken", exchange -> answer(exchange, 503));
		server.createContext("/moved", exchange -> {
			exchange.getResponseHeaders().add("Location", "http://203.0.113.10/elsewhere");
			answer(exchange, 302);
		});
		// Answers at once, then keeps sending a byte every half second for up to 15 seconds.
		server.createContext("/streaming", exchange -> {
			requests.incrementAndGet();
			exchange.sendResponseHeaders(200, 0);
			try (var body = exchange.getResponseBody()) {
				for (int i = 0; i < 30; i++) {
					body.write('x');
					body.flush();
					Thread.sleep(500);
				}
			}
			catch (IOException | InterruptedException e) {
				// The probe closed the connection: what this stub is for.
			}
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopStub() {
		server.stop(0);
	}

	@Test
	@DisplayName("The expected status passes")
	void expectedStatusPasses() {
		ProbeOutcome outcome = probe(true).probe(base + "/ok", 200);

		assertThat(outcome.success()).isTrue();
		assertThat(outcome.statusCode()).isEqualTo(200);
		assertThat(outcome.error()).isNull();
	}

	@Test
	@DisplayName("Another status fails and says what came back")
	void otherStatusFails() {
		ProbeOutcome outcome = probe(true).probe(base + "/broken", 200);

		assertThat(outcome.success()).isFalse();
		assertThat(outcome.statusCode()).isEqualTo(503);
		assertThat(outcome.error()).isEqualTo("Expected HTTP 200, got 503");
	}

	@Test
	@DisplayName("A redirect is not followed: it counts as its own status")
	void redirectsAreNotFollowed() {
		ProbeOutcome outcome = probe(true).probe(base + "/moved", 200);

		assertThat(outcome.success()).isFalse();
		assertThat(outcome.statusCode()).isEqualTo(302);
	}

	@Test
	@DisplayName("A body that keeps coming does not hold the check: the status decides as soon as it arrives")
	void streamingBodyDoesNotHoldTheCheck() {
		long started = System.nanoTime();

		ProbeOutcome outcome = probe(true).probe(base + "/streaming", 200);

		assertThat(outcome.success()).isTrue();
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
	}

	@Test
	@DisplayName("Nothing listening is a failure with no status")
	void refusedConnectionFails() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}

		ProbeOutcome outcome = probe(true).probe("http://127.0.0.1:" + closedPort + "/", 200);

		assertThat(outcome.success()).isFalse();
		assertThat(outcome.statusCode()).isNull();
		assertThat(outcome.error()).startsWith("Could not connect");
	}

	@Test
	@DisplayName("TC-FR-22.1f: A refused target is never contacted")
	void refusedTargetIsNotContacted() {
		ProbeOutcome outcome = probe(false).probe(base + "/ok", 200);

		assertThat(outcome.success()).isFalse();
		assertThat(outcome.error()).startsWith("Not checked:");
		assertThat(requests).hasValue(0);
	}

	private HttpProbe probe(boolean allowPrivateTargets) {
		return new HttpProbe(new MonitorTargetGuard(allowPrivateTargets), CLOCK);
	}

	private void answer(com.sun.net.httpserver.HttpExchange exchange, int status) throws IOException {
		requests.incrementAndGet();
		exchange.sendResponseHeaders(status, -1);
		exchange.close();
	}
}
