package com.settle.notification.dto;

import java.util.List;

public record NotificationListResponse(
		List<NotificationResponse> notifications,
		long unreadCount
) {
}
