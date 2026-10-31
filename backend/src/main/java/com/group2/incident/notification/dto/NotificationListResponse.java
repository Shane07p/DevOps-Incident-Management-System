package com.group2.incident.notification.dto;

import java.util.List;

public record NotificationListResponse(
		List<NotificationResponse> notifications,
		long unreadCount
) {
}
