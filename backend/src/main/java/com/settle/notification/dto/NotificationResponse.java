package com.settle.notification.dto;

import java.time.Instant;

public record NotificationResponse(
		Long id,
		Long incidentId,
		String message,
		Instant createdAt,
		Instant readAt
) {
}
