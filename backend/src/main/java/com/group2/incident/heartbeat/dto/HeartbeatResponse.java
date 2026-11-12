package com.group2.incident.heartbeat.dto;

import com.group2.incident.heartbeat.entity.Heartbeat;
import java.time.Instant;

public record HeartbeatResponse(
		Long id,
		String name,
		Long serviceId,
		String serviceName,
		int intervalSeconds,
		int graceSeconds,
		String token,
		String pingUrl,
		boolean enabled,
		Instant lastPingAt,
		Instant nextDueAt,
		Instant lateAfter,
		Instant outageStartedAt,
		Long activeAlertId,
		Instant createdAt,
		Instant updatedAt
) {

	public static HeartbeatResponse from(Heartbeat heartbeat) {
		return new HeartbeatResponse(
				heartbeat.getId(),
				heartbeat.getName(),
				heartbeat.getService().getId(),
				heartbeat.getService().getName(),
				heartbeat.getIntervalSeconds(),
				heartbeat.getGraceSeconds(),
				heartbeat.getToken(),
				"/api/public/heartbeats/ping/" + heartbeat.getToken(),
				heartbeat.isEnabled(),
				heartbeat.getLastPingAt(),
				heartbeat.getNextDueAt(),
				heartbeat.getLateAfter(),
				heartbeat.getOutageStartedAt(),
				heartbeat.getActiveAlertId(),
				heartbeat.getCreatedAt(),
				heartbeat.getUpdatedAt()
		);
	}
}
