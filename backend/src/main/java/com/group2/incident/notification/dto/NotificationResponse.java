package com.group2.incident.notification.dto;

import java.time.Instant;

public record NotificationResponse(
		Long id,
		Long incidentId,
		String message,
		Instant createdAt,
		Instant readAt
) {
}
