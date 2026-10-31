package com.group2.incident.notification.service;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.notification.dto.NotificationListResponse;
import com.group2.incident.notification.dto.NotificationResponse;
import com.group2.incident.notification.entity.Notification;
import com.group2.incident.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

	private final NotificationRepository notificationRepository;
	private final Clock clock;

	/**
	 * Creates a notification inside the caller's existing transaction.
	 * If that transaction rolls back, the notification is rolled back too.
	 * This method must be called from within a @Transactional method.
	 */
	public Notification createNotification(AppUser recipient, Incident incident, String message) {
		Instant now = clock.instant();
		Notification notification = new Notification(recipient, incident, message, now);
		return notificationRepository.save(notification);
	}

	@Transactional(readOnly = true)
	public NotificationListResponse getNotificationsForUser(Long userId) {
		List<Notification> notifications = notificationRepository
				.findAllByRecipientIdOrderByCreatedAtDescIdDesc(userId);
		long unreadCount = notificationRepository.countUnreadByRecipientId(userId);

		List<NotificationResponse> responses = notifications.stream()
				.map(this::toResponse)
				.toList();
		return new NotificationListResponse(responses, unreadCount);
	}

	@Transactional
	public NotificationResponse markAsRead(Long notificationId, Long userId) {
		Notification notification = notificationRepository.findById(notificationId)
				.orElseThrow(() -> new NotFoundException("Notification " + notificationId + " not found"));

		if (!notification.getRecipient().getId().equals(userId)) {
			// Return 404 so the caller cannot discover that the notification exists (user isolation).
			throw new NotFoundException("Notification " + notificationId + " not found");
		}

		notification.markRead(clock.instant());
		return toResponse(notificationRepository.save(notification));
	}

	private NotificationResponse toResponse(Notification notification) {
		Long incidentId = notification.getIncident() != null ? notification.getIncident().getId() : null;
		return new NotificationResponse(
				notification.getId(),
				incidentId,
				notification.getMessage(),
				notification.getCreatedAt(),
				notification.getReadAt()
		);
	}
}
