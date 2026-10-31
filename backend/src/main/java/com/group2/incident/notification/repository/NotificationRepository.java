package com.group2.incident.notification.repository;

import com.group2.incident.notification.entity.Notification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	List<Notification> findAllByRecipientIdOrderByCreatedAtDescIdDesc(Long recipientId);

	@Query("SELECT COUNT(n) FROM Notification n WHERE n.recipient.id = :recipientId AND n.readAt IS NULL")
	long countUnreadByRecipientId(Long recipientId);
}
