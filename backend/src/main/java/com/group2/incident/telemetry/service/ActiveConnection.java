package com.group2.incident.telemetry.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The current org's connection, ready to call: addresses checked, token decrypted. Lives only for one request;
 * never logged, never returned.
 *
 * @param prometheus null when the org connected only logs
 * @param loki null when the org connected only metrics
 */
public record ActiveConnection(
		URI prometheus,
		String prometheusUser,
		URI loki,
		String lokiUser,
		String token,
		Naming naming
) {

	/** How the org's own telemetry is named. Each value was checked against Prometheus's name rules when saved. */
	public record Naming(String serviceLabel, String requestsMetric, String statusLabel, String durationMetric) {
	}

	/** Basic with the store's user, as Grafana Cloud expects, or Bearer when there is no user. */
	String authorization(String user) {
		if (user != null && !user.isBlank()) {
			return "Basic " + Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
		}
		return "Bearer " + token;
	}

	@Override
	public String toString() {
		return "ActiveConnection[prometheus=" + prometheus + ", loki=" + loki + ", token=<hidden>]";
	}
}
