package com.group2.incident.telemetry.controller;

import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.telemetry.dto.LogEntryResponse;
import com.group2.incident.telemetry.dto.MetricSeriesResponse;
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

@RestController
@RequestMapping("/api/telemetry")
@Tag(name = "Telemetry", description = "A service's logs and metrics from Grafana Cloud, read with the backend's token (D34)")
@RequiredArgsConstructor
public class TelemetryController {

	private final TelemetryService telemetryService;

	@GetMapping("/logs")
	@Operation(summary = "Search a service's logs (FR-26): newest first, at most 24 hours")
	public List<LogEntryResponse> queryLogs(
			@Parameter(description = "A service of your org") @RequestParam long serviceId,
			@Parameter(description = "DEBUG, INFO, WARN, or ERROR") @RequestParam(required = false) String level,
			@Parameter(description = "Text matched literally") @RequestParam(required = false) String text,
			@Parameter(description = "Start, ISO-8601 UTC; the default is an hour before the end") @RequestParam(required = false) String from,
			@Parameter(description = "End, ISO-8601 UTC; the default is now") @RequestParam(required = false) String to,
			@Parameter(description = "At most this many lines, 1 to 1000") @RequestParam(defaultValue = "100") int limit
	) {
		return telemetryService.queryLogs(serviceId, level, text, instant(from, "from"), instant(to, "to"), limit);
	}

	@GetMapping("/metrics")
	@Operation(summary = "One metric of a service (FR-27): requestRate, errorRate, latencyP50, or latencyP95")
	public MetricSeriesResponse queryMetric(
			@Parameter(description = "A service of your org") @RequestParam long serviceId,
			@Parameter(description = "requestRate, errorRate, latencyP50, or latencyP95") @RequestParam String metric,
			@Parameter(description = "Start, ISO-8601 UTC; the default is an hour before the end") @RequestParam(required = false) String from,
			@Parameter(description = "End, ISO-8601 UTC; the default is now") @RequestParam(required = false) String to,
			@Parameter(description = "Seconds between points, or 30s, 5m, 1h; 15s to 1h") @RequestParam(defaultValue = "60s") String step
	) {
		return telemetryService.queryMetric(serviceId, metric, instant(from, "from"), instant(to, "to"), step);
	}

	private static Instant instant(String value, String field) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException e) {
			throw new FieldValidationException(field, "must be an ISO-8601 UTC time, such as 2026-11-10T08:00:00Z");
		}
	}
}
