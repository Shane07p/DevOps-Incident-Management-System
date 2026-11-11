package com.group2.incident.telemetry.dto;

import java.time.Instant;
import java.util.Map;

/**
 * One log line of a service (FR-26.1). {@code message} is the line's message or event, or the whole line
 * when it is not JSON; {@code attributes} are the line's other JSON fields.
 */
public record LogEntryResponse(
		String id,
		Instant timestamp,
		Long serviceId,
		String serviceName,
		String level,
		String message,
		Map<String, Object> attributes
) {
}
