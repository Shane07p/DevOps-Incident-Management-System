package com.group2.incident.monitor.dto;

import com.group2.incident.monitor.entity.UptimeMonitor;
import java.time.Instant;

public record MonitorResponse(
		Long id,
		String name,
		String url,
		int expectedStatus,
		int intervalSeconds,
		Long serviceId,
		String serviceName,
		int failureThreshold,
		boolean enabled,
		int consecutiveFailures,
		Instant outageStartedAt,
		Long activeAlertId,
		Instant nextCheckAt,
		Instant createdAt,
		Instant updatedAt
) {

	public static MonitorResponse from(UptimeMonitor monitor) {
		return new MonitorResponse(
				monitor.getId(),
				monitor.getName(),
				monitor.getUrl(),
				monitor.getExpectedStatus(),
				monitor.getIntervalSeconds(),
				monitor.getService().getId(),
				monitor.getService().getName(),
				monitor.getFailureThreshold(),
				monitor.isEnabled(),
				monitor.getConsecutiveFailures(),
				monitor.getOutageStartedAt(),
				monitor.getActiveAlertId(),
				monitor.getNextCheckAt(),
				monitor.getCreatedAt(),
				monitor.getUpdatedAt()
		);
	}
}
