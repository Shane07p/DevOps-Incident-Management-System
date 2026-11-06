package com.group2.incident.monitor.service;

import com.group2.incident.common.error.FieldValidationException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * One uptime check: a GET that must answer with the monitor's expected status. Redirects are not
 * followed, so a redirect counts as its own status and cannot lead the check to another host.
 */
@Component
public class HttpProbe {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

	private final MonitorTargetGuard guard;

	private final Clock clock;

	private final HttpClient client;

	public HttpProbe(MonitorTargetGuard guard, Clock clock) {
		this.guard = guard;
		this.clock = clock;
		this.client = HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	public ProbeOutcome probe(String url, int expectedStatus) {
		URI target;
		try {
			target = guard.check(url);
		}
		catch (FieldValidationException e) {
			return ProbeOutcome.failed(null, null, "Not checked: " + e.getMessage());
		}
		HttpRequest request = HttpRequest.newBuilder(target)
				.timeout(REQUEST_TIMEOUT)
				.header("User-Agent", "Settle-uptime-monitor")
				.GET()
				.build();
		long started = clock.millis();
		try {
			int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
			long elapsed = clock.millis() - started;
			if (status == expectedStatus) {
				return ProbeOutcome.passed(status, elapsed);
			}
			return ProbeOutcome.failed(status, elapsed, "Expected HTTP " + expectedStatus + ", got " + status);
		}
		catch (HttpTimeoutException e) {
			return ProbeOutcome.failed(null, clock.millis() - started, "No answer within " + REQUEST_TIMEOUT.toSeconds() + " seconds");
		}
		catch (IOException e) {
			return ProbeOutcome.failed(null, null, "Could not connect: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return ProbeOutcome.failed(null, null, "The check was interrupted");
		}
	}
}
