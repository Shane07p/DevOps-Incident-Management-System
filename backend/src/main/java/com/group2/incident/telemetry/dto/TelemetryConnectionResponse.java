package com.group2.incident.telemetry.dto;

import java.time.Instant;

/**
 * The org's Grafana connection as an Admin sees it. The token is never returned: {@code tokenSaved} only says
 * one is there. With no connection, {@code connected} is false and the naming fields show the defaults.
 */
public record TelemetryConnectionResponse(
		boolean connected,
		String prometheusUrl,
		String prometheusUser,
		String lokiUrl,
		String lokiUser,
		boolean tokenSaved,
		String serviceLabel,
		String requestsMetric,
		String statusLabel,
		String durationMetric,
		Instant updatedAt,
		String updatedBy
) {
}
