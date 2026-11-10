package com.group2.incident.telemetry.dto;

import java.time.Instant;
import java.util.List;

/**
 * A single metric series query response (FR-27.1).
 */
public record MetricSeriesResponse(
		String metric,
		String service,
		Instant from,
		Instant to,
		List<MetricDataPoint> points
) {
}
