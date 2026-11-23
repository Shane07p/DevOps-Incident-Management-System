package com.settle.incident.dto;

import com.settle.incident.entity.TimelineEventType;
import java.time.Instant;

public record TimelineEntryResponse(
		long id,
		TimelineEventType eventType,
		String actor,
		String details,
		Instant recordedAt,
		Long correctsEntryId
) {
}
