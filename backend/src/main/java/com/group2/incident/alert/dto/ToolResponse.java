package com.group2.incident.alert.dto;

import com.group2.incident.alert.entity.MonitoringTool;
import java.time.Instant;

public record ToolResponse(
		Long id,
		String name,
		Instant createdAt,
		Instant revokedAt
) {
	public static ToolResponse from(MonitoringTool tool) {
		return new ToolResponse(
				tool.getId(),
				tool.getName(),
				tool.getCreatedAt(),
				tool.getRevokedAt()
		);
	}
}
