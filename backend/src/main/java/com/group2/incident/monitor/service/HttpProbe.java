package com.group2.incident.monitor.service;

import com.group2.incident.common.error.FieldValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
		// The whole call, DNS to headers, has one deadline, so no target can hold the worker longer.
		CompletableFuture<HttpResponse<InputStream>> call = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
		try {
			HttpResponse<InputStream> response = call.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			// Only the status matters. Closing the body unread stops the download, so a slow or endless
			// body cannot keep the check running after the headers have arrived.
			response.body().close();
			int status = response.statusCode();
			long elapsed = clock.millis() - started;
			if (status == expectedStatus) {
				return ProbeOutcome.passed(status, elapsed);
			}
			return ProbeOutcome.failed(status, elapsed, "Expected HTTP " + expectedStatus + ", got " + status);
		}
		catch (TimeoutException e) {
			call.cancel(true);
			return timedOut(started);
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof HttpTimeoutException) {
				return timedOut(started);
			}
			return couldNotConnect(cause != null ? cause : e);
		}
		catch (IOException e) {
			return couldNotConnect(e);
		}
		catch (InterruptedException e) {
			call.cancel(true);
			Thread.currentThread().interrupt();
			return ProbeOutcome.failed(null, null, "The check was interrupted");
		}
	}

	private ProbeOutcome timedOut(long started) {
		return ProbeOutcome.failed(null, clock.millis() - started, "No answer within " + REQUEST_TIMEOUT.toSeconds() + " seconds");
	}

	private static ProbeOutcome couldNotConnect(Throwable e) {
		return ProbeOutcome.failed(null, null, "Could not connect: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
	}
}
