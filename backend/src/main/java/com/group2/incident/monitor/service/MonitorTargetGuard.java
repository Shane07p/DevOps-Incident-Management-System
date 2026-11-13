package com.group2.incident.monitor.service;

import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.net.InternalAddresses;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps monitors pointed at the outside world. A monitor makes the backend send a request, so a URL
 * naming our own network (loopback, private ranges, link-local, which holds the cloud metadata address
 * 169.254.169.254) would let an Admin probe what only the server can reach. Checked when a monitor is
 * saved and again before every check, because a name can resolve somewhere else later.
 *
 * <p>{@code app.monitors.allow-private-targets} turns the address check off for local runs against
 * {@code localhost}; production leaves it off.
 */
@Component
public class MonitorTargetGuard {

	private final boolean allowPrivateTargets;

	public MonitorTargetGuard(@Value("${app.monitors.allow-private-targets:false}") boolean allowPrivateTargets) {
		this.allowPrivateTargets = allowPrivateTargets;
	}

	/** The URL to request, or a {@link FieldValidationException} on {@code url} saying why it is refused. */
	public URI check(String url) {
		URI uri = parse(url);
		if (allowPrivateTargets) {
			return uri;
		}
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(uri.getHost());
		}
		catch (UnknownHostException e) {
			throw new FieldValidationException("url", "The host " + uri.getHost() + " could not be found");
		}
		for (InetAddress address : addresses) {
			if (InternalAddresses.isInternal(address)) {
				throw new FieldValidationException("url", "The URL must not point to a private or local network address");
			}
		}
		return uri;
	}

	private static URI parse(String url) {
		URI uri;
		try {
			uri = new URI(url.strip());
		}
		catch (URISyntaxException e) {
			throw new FieldValidationException("url", "Enter a valid http or https URL");
		}
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
				|| uri.getHost() == null || uri.getHost().isBlank()) {
			throw new FieldValidationException("url", "Enter a valid http or https URL");
		}
		if (uri.getRawUserInfo() != null) {
			throw new FieldValidationException("url", "The URL must not contain a user name or password");
		}
		return uri;
	}
}
