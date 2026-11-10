package com.group2.incident.telemetry.controller;

import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.telemetry.dto.LogEntryResponse;
import com.group2.incident.telemetry.dto.ServiceMetricsResponse;
import com.group2.incident.telemetry.service.TelemetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for logs (FR-26) and metrics (FR-27) API through the backend.
 * Uses a server-side read token (D34); tokens and LogQL/PromQL are never exposed to clients.
 */
@RestController
@RequestMapping("/api/telemetry")
@Tag(name = "Telemetry", description = "Query logs and metrics through backend-managed Grafana Cloud read tokens (D34)")
@RequiredArgsConstructor
public class TelemetryController {

	private final TelemetryService telemetryService;

	@GetMapping("/logs")
	@Operation(summary = "Search service logs from Grafana Loki (FR-26)")
	public List<LogEntryResponse> queryLogs(
			@Parameter(description = "Database ID of the service to query")
			@RequestParam(required = false) Long serviceId,

			@Parameter(description = "Service name to query (alternative to serviceId)")
			@RequestParam(required = false) String service,

			@Parameter(description = "Log level filter (DEBUG, INFO, WARN, ERROR)")
			@RequestParam(required = false) String level,

			@Parameter(description = "Text search query (matched literally, never parsed as LogQL)")
			@RequestParam(required = false) String text,

			@Parameter(description = "Start timestamp (ISO-8601 UTC)")
			@RequestParam(required = false) String from,

			@Parameter(description = "End timestamp (ISO-8601 UTC)")
			@RequestParam(required = false) String to,

			@Parameter(description = "Maximum number of log entries to return (default 100, max 1000)")
			@RequestParam(required = false, defaultValue = "100") Integer limit
	) {
		Instant parsedFrom = parseInstant(from, "from");
		Instant parsedTo = parseInstant(to, "to");
		return telemetryService.queryLogs(serviceId, service, level, text, parsedFrom, parsedTo, limit);
	}

	@GetMapping("/metrics")
	@Operation(summary = "Query service metrics from Prometheus (FR-27)")
	public ServiceMetricsResponse queryMetrics(
			@Parameter(description = "Database ID of the service to query")
			@RequestParam(required = false) Long serviceId,

			@Parameter(description = "Service name to query (alternative to serviceId)")
			@RequestParam(required = false) String service,

			@Parameter(description = "Start timestamp (ISO-8601 UTC)")
			@RequestParam(required = false) String from,

			@Parameter(description = "End timestamp (ISO-8601 UTC)")
			@RequestParam(required = false) String to,

			@Parameter(description = "Prometheus step resolution (default '60s')")
			@RequestParam(required = false, defaultValue = "60s") String step
	) {
		Instant parsedFrom = parseInstant(from, "from");
		Instant parsedTo = parseInstant(to, "to");
		return telemetryService.queryMetrics(serviceId, service, parsedFrom, parsedTo, step);
	}

	private static Instant parseInstant(String raw, String fieldName) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(raw);
		} catch (DateTimeParseException e) {
			throw new FieldValidationException(fieldName,
					"'" + fieldName + "' must be an ISO-8601 UTC timestamp (e.g. 2026-10-01T00:00:00Z)");
		}
	}
}
