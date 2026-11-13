package com.group2.incident.telemetry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.telemetry.config.TelemetryProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The guard on org-supplied Grafana addresses (#260, Codex D03), with private targets not allowed. */
class TelemetryHttpTest {

	private final TelemetryHttp guarded = new TelemetryHttp(new TelemetryProperties("", false, 2));

	private HttpServer stub;

	private final AtomicInteger requests = new AtomicInteger();

	@BeforeEach
	void startStub() throws IOException {
		stub = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		stub.createContext("/", exchange -> {
			requests.incrementAndGet();
			byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		stub.start();
	}

	@AfterEach
	void stop() throws IOException {
		stub.stop(0);
		guarded.destroy();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"https://127.0.0.1/api/prom",          // loopback
			"https://localhost/api/prom",          // a name that resolves to loopback
			"https://10.1.2.3",                    // private
			"https://192.168.0.10",                // private
			"https://169.254.169.254/latest",      // link-local: the cloud metadata address
			"https://100.64.0.1",                  // shared address space
			"https://[::1]/",                      // IPv6 loopback
			"https://[fd00::1]/" })                // IPv6 unique local
	void internalAddressesAreRefusedWhenSaved(String url) {
		assertThatThrownBy(() -> guarded.checkUrl("prometheusUrl", url))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("private or local network address");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://93.184.215.14/api/prom",       // plain http
			"ftp://93.184.215.14/",                // not a web URL
			"https:///no-host",                    // no host
			"not a url at all" })
	void onlyHttpsWithAHostIsAccepted(String url) {
		assertThatThrownBy(() -> guarded.checkUrl("lokiUrl", url))
				.isInstanceOf(FieldValidationException.class)
				.hasMessage("Enter a valid https URL");
	}

	@Test
	void userInfoQueryAndFragmentAreRefused() {
		assertThatThrownBy(() -> guarded.checkUrl("lokiUrl", "https://user:pass@93.184.215.14/"))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("own field");
		assertThatThrownBy(() -> guarded.checkUrl("lokiUrl", "https://93.184.215.14/?x=1"))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("query or a fragment");
	}

	@Test
	void aPublicHttpsAddressIsAccepted() {
		assertThat(guarded.checkUrl("prometheusUrl", " https://93.184.215.14/api/prom "))
				.isEqualTo(URI.create("https://93.184.215.14/api/prom"));
	}

	/**
	 * DNS rebinding: a name that passed when saved and points at our own machine by the time of the call. The
	 * client's resolver refuses it while connecting, so the request never leaves.
	 */
	@Test
	void theConnectionItselfRefusesAnInternalAddressEvenIfTheCheckWasSkipped() {
		URI rebound = URI.create("http://localhost:" + stub.getAddress().getPort());

		assertThatThrownBy(() -> guarded.get(rebound, "/api/v1/query", Map.of("query", "1"), null, "Metrics"))
				.isInstanceOf(ServiceUnavailableException.class)
				.hasMessage("Metrics are slow or unavailable right now");
		assertThat(requests.get()).isZero();
	}

	@Test
	void withPrivateTargetsAllowedTheSameCallGoesThrough() throws IOException {
		TelemetryHttp local = new TelemetryHttp(new TelemetryProperties("", true, 2));
		try {
			URI base = URI.create("http://localhost:" + stub.getAddress().getPort());
			assertThat(local.checkUrl("lokiUrl", base.toString())).isEqualTo(base);
			assertThat(local.get(base, "/loki/api/v1/labels", Map.of(), null, "Logs")).isEqualTo("{}");
			assertThat(requests.get()).isEqualTo(1);
		}
		finally {
			local.destroy();
		}
	}

	@Test
	void aRedirectIsNotFollowed() throws IOException {
		HttpServer redirecting = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		redirecting.createContext("/", exchange -> {
			exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + stub.getAddress().getPort() + "/");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		redirecting.start();
		TelemetryHttp local = new TelemetryHttp(new TelemetryProperties("", true, 2));
		try {
			URI base = URI.create("http://127.0.0.1:" + redirecting.getAddress().getPort());
			assertThatThrownBy(() -> local.get(base, "/", Map.of(), null, "Logs"))
					.isInstanceOf(ServiceUnavailableException.class)
					.hasMessage("Logs are unavailable right now");
			assertThat(requests.get()).isZero();
		}
		finally {
			redirecting.stop(0);
			local.destroy();
		}
	}
}
