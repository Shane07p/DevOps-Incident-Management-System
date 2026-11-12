package com.group2.incident.telemetry.service;

import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.common.net.InternalAddresses;
import com.group2.incident.telemetry.config.TelemetryProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The only way telemetry reaches an org's Grafana. An org types these addresses in, so the backend would be
 * sending requests wherever an Admin points it. Two checks stop that from reaching our own network:
 * <ul>
 * <li>{@link #checkUrl} when a connection is saved and before every call: https only, a host, no user info,
 * query, or fragment, and no address that is loopback, private, link-local (the cloud metadata address), or
 * otherwise internal;</li>
 * <li>the client's DNS resolver, which refuses the same addresses when connecting, and hands the connection
 * the addresses it checked. A name that resolves somewhere public at save time and to 127.0.0.1 a second
 * later is refused on the second lookup too (DNS rebinding, Codex D03).</li>
 * </ul>
 * Redirects are never followed, and the whole call, from connecting to the last byte, ends within the timeout.
 * {@code app.telemetry.allow-private-targets} turns the address and https checks off for local runs.
 */
@Component
@Slf4j
public class TelemetryHttp implements DisposableBean {

	/** Larger answers are refused; the biggest real one (1,000 log lines or 5,760 points) is far smaller. */
	static final int MAX_BODY_CHARS = 8 * 1024 * 1024;

	private final boolean allowPrivateTargets;

	private final Duration timeout;

	private final CloseableHttpClient client;

	private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "telemetry-deadline");
		thread.setDaemon(true);
		return thread;
	});

	public TelemetryHttp(TelemetryProperties properties) {
		this.allowPrivateTargets = properties.allowPrivateTargets();
		this.timeout = Duration.ofSeconds(properties.timeoutSeconds());
		Timeout limit = Timeout.of(timeout);
		this.client = HttpClients.custom()
				.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
						.setDnsResolver(new GuardedResolver(allowPrivateTargets))
						.setDefaultConnectionConfig(ConnectionConfig.custom()
								.setConnectTimeout(limit)
								.setSocketTimeout(limit)
								.build())
						.build())
				.setDefaultRequestConfig(RequestConfig.custom()
						.setConnectionRequestTimeout(limit)
						.setResponseTimeout(limit)
						.build())
				.disableRedirectHandling()
				.disableAutomaticRetries()
				.disableCookieManagement()
				.disableAuthCaching()
				.build();
	}

	/** The base address to call, or a {@link FieldValidationException} on {@code field} saying why it is refused. */
	public URI checkUrl(String field, String url) {
		URI uri;
		try {
			uri = new URI(url.strip());
		}
		catch (URISyntaxException e) {
			throw new FieldValidationException(field, "Enter a valid https URL");
		}
		String scheme = uri.getScheme();
		boolean https = "https".equalsIgnoreCase(scheme);
		boolean http = "http".equalsIgnoreCase(scheme);
		if (!(https || (http && allowPrivateTargets)) || uri.getHost() == null || uri.getHost().isBlank()) {
			throw new FieldValidationException(field, "Enter a valid https URL");
		}
		if (uri.getRawUserInfo() != null) {
			throw new FieldValidationException(field, "Put the user in its own field, not in the URL");
		}
		if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
			throw new FieldValidationException(field, "The URL must not have a query or a fragment");
		}
		if (!allowPrivateTargets) {
			try {
				GuardedResolver.publicAddresses(uri.getHost());
			}
			catch (UnknownHostException e) {
				throw new FieldValidationException(field, e.getMessage());
			}
		}
		return uri;
	}

	/**
	 * One GET under {@code base}: the path is ours and the parameters are encoded after a fixed "?", so nothing
	 * a caller sends can point the request somewhere else. Failures name the store, never the token or the body.
	 */
	public String get(URI base, String path, Map<String, String> params, String authorization, String store) {
		String query = params.entrySet().stream()
				.map(param -> param.getKey() + "=" + URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
		HttpGet request = new HttpGet(URI.create(base.toString().replaceAll("/+$", "") + path + "?" + query));
		if (authorization != null) {
			request.setHeader("Authorization", authorization);
		}
		ScheduledFuture<?> deadline = deadlines.schedule(request::cancel, timeout.toMillis(), TimeUnit.MILLISECONDS);
		try {
			return client.execute(request, response -> {
				if (response.getCode() / 100 != 2) {
					log.warn("{} store answered HTTP {}", store, response.getCode());
					throw new ServiceUnavailableException(store + " are unavailable right now");
				}
				try {
					return EntityUtils.toString(response.getEntity(), MAX_BODY_CHARS);
				}
				catch (ParseException e) {
					throw new IOException("Unreadable answer", e);
				}
			});
		}
		catch (IOException | RuntimeException e) {
			if (e instanceof ServiceUnavailableException unavailable) {
				throw unavailable;
			}
			log.warn("{} store is slow, unreachable, or refused: {}", store, e.getClass().getSimpleName());
			throw new ServiceUnavailableException(store + " are slow or unavailable right now", e);
		}
		finally {
			deadline.cancel(false);
		}
	}

	@Override
	public void destroy() throws IOException {
		deadlines.shutdownNow();
		client.close();
	}

	/** Resolves a host and refuses it when any of its addresses is internal; the connection uses what it returns. */
	static final class GuardedResolver implements DnsResolver {

		private final boolean allowPrivateTargets;

		GuardedResolver(boolean allowPrivateTargets) {
			this.allowPrivateTargets = allowPrivateTargets;
		}

		@Override
		public InetAddress[] resolve(String host) throws UnknownHostException {
			return allowPrivateTargets ? SystemDefaultDnsResolver.INSTANCE.resolve(host) : publicAddresses(host);
		}

		@Override
		public String resolveCanonicalHostname(String host) throws UnknownHostException {
			return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
		}

		static InetAddress[] publicAddresses(String host) throws UnknownHostException {
			InetAddress[] addresses;
			try {
				addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host);
			}
			catch (UnknownHostException e) {
				throw new UnknownHostException("The host " + host + " could not be found");
			}
			for (InetAddress address : addresses) {
				if (InternalAddresses.isInternal(address)) {
					throw new UnknownHostException("The URL must not point to a private or local network address");
				}
			}
			return addresses;
		}
	}
}
