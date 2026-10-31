package com.group2.incident.notification.controller;

import com.group2.incident.notification.dto.NotificationListResponse;
import com.group2.incident.notification.dto.NotificationResponse;
import com.group2.incident.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "In-app notification management")
@RequiredArgsConstructor
public class NotificationController {

	private final NotificationService notificationService;

	@GetMapping
	@Operation(summary = "List notifications for the authenticated user")
	public NotificationListResponse getNotifications(@AuthenticationPrincipal Jwt jwt) {
		Long userId = Long.parseLong(jwt.getSubject());
		return notificationService.getNotificationsForUser(userId);
	}

	@PostMapping("/{id}/read")
	@Operation(summary = "Mark a notification as read")
	public NotificationResponse markAsRead(
			@PathVariable Long id,
			@AuthenticationPrincipal Jwt jwt) {
		Long userId = Long.parseLong(jwt.getSubject());
		return notificationService.markAsRead(id, userId);
	}
}
