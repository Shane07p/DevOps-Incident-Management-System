package com.group2.incident.alert.dto;

import java.time.Instant;

public record AlertInboxItemResponse(
		Long id,
		String source,
		String serviceName,
		Long incidentId,
		// Whoever the incident is assigned to, so the inbox can show what is routed to the viewer.
		Long assigneeId,
		String status,
		String correlationKey,
		Instant createdAt,
		// Alerts grouped into the same incident, this one included.
		long count
) {}
