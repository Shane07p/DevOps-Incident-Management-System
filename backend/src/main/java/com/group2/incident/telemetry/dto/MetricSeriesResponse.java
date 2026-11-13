package com.group2.incident.telemetry.dto;

import java.time.Instant;
import java.util.List;

/**
 * One metric of one service over a time range (FR-27.1): {@code requestRate}, {@code errorRate}, {@code latencyP50},
 * or {@code latencyP95}, a point every {@code stepSeconds}. Moments with no value (no traffic) have no point.
 */
public record MetricSeriesResponse(
		String metric,
		Long serviceId,
		String serviceName,
		Instant from,
		Instant to,
		long stepSeconds,
		List<MetricDataPoint> points
) {
}
