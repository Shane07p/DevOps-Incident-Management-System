package com.group2.incident.telemetry.dto;

import java.time.Instant;
import java.util.List;

/**
 * Service metric series for request rate, error rate, and latency (FR-27.1).
 */
public record ServiceMetricsResponse(
		Long serviceId,
		String serviceName,
		Instant from,
		Instant to,
		List<MetricDataPoint> requestRate,
		List<MetricDataPoint> errorRate,
		List<MetricDataPoint> latencyP50,
		List<MetricDataPoint> latencyP95
) {
}
