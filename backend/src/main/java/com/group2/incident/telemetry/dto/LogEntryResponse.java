package com.group2.incident.telemetry.dto;

import java.time.Instant;
import java.util.Map;

/**
 * A single log entry from Grafana Loki (FR-26.1).
 */
public record LogEntryResponse(
		String id,
		Instant timestamp,
		String serviceName,
		Long serviceId,
		String level,
		String message,
		Map<String, Object> attributes
) {
}
