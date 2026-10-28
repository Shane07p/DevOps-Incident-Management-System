package com.group2.incident.incident.dto;

import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.incident.entity.IncidentStatus;
import java.time.Instant;
import java.util.List;

public record IncidentResponse(
		long id,
		String reference,
		String title,
		String description,
		IncidentServiceDto service,
		Severity severity,
		IncidentStatus status,
		IncidentUserDto reporter,
		IncidentUserDto assignee,
		Instant createdAt,
		int responseTargetMinutes,
		int resolutionTargetMinutes,
		Instant responseDueAt,
		Instant resolutionDueAt,
		List<TimelineEntryResponse> timeline
) {
}
