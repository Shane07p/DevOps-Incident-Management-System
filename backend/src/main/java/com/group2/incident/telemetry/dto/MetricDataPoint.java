package com.group2.incident.telemetry.dto;

import java.time.Instant;

/**
 * A timestamped numeric measurement from Prometheus (FR-27.1).
 */
public record MetricDataPoint(
		Instant timestamp,
		double value
) {
}
