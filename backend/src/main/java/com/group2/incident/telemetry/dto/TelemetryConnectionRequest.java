package com.group2.incident.telemetry.dto;

import jakarta.validation.constraints.Size;

/**
 * An Admin connecting the org's own Grafana (D49). Leave a store's URL empty to connect only the other one.
 * {@code token} is required the first time; leave it empty later to keep the one already saved. The naming
 * fields default to common Prometheus conventions when empty.
 */
public record TelemetryConnectionRequest(
		@Size(max = 500, message = "must be at most 500 characters") String prometheusUrl,
		@Size(max = 100, message = "must be at most 100 characters") String prometheusUser,
		@Size(max = 500, message = "must be at most 500 characters") String lokiUrl,
		@Size(max = 100, message = "must be at most 100 characters") String lokiUser,
		@Size(max = 2000, message = "must be at most 2000 characters") String token,
		@Size(max = 100, message = "must be at most 100 characters") String serviceLabel,
		@Size(max = 200, message = "must be at most 200 characters") String requestsMetric,
		@Size(max = 100, message = "must be at most 100 characters") String statusLabel,
		@Size(max = 200, message = "must be at most 200 characters") String durationMetric
) {
}
