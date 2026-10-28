package com.group2.incident.incident.dto;

import com.group2.incident.incident.entity.TimelineEventType;
import java.time.Instant;

public record TimelineEntryResponse(
		long id,
		TimelineEventType eventType,
		String actor,
		String details,
		Instant recordedAt
) {
}
