package com.group2.incident.notification.repository;

import com.group2.incident.notification.entity.Notification;
import com.group2.incident.notification.entity.NotificationKind;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	List<Notification> findAllByRecipientIdOrderByCreatedAtDescIdDesc(Long recipientId);

	@Query("SELECT COUNT(n) FROM Notification n WHERE n.recipient.id = :recipientId AND n.readAt IS NULL")
	long countUnreadByRecipientId(Long recipientId);

	@Query("SELECT n FROM Notification n WHERE n.incident IS NOT NULL AND n.kind = :kind AND n.createdAt >= :from AND n.createdAt < :to")
	List<Notification> findAllIncidentNotificationsInWindow(
			@Param("from") Instant from,
			@Param("to") Instant to,
			@Param("kind") NotificationKind kind);

	default List<Notification> findAllPagesInWindow(Instant from, Instant to) {
		return findAllIncidentNotificationsInWindow(from, to, NotificationKind.PAGE);
	}
}
