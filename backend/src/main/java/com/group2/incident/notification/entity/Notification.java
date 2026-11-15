package com.group2.incident.notification.entity;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.incident.entity.Incident;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Filled and filtered by Hibernate from OrgContext (D48); never set by hand.
	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "recipient_id", nullable = false)
	private AppUser recipient;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "incident_id")
	private Incident incident;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, length = 30)
	private NotificationKind kind = NotificationKind.SYSTEM;

	@Column(nullable = false, length = 500)
	private String message;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "read_at")
	private Instant readAt;

	public Notification(AppUser recipient, Incident incident, String message, Instant createdAt) {
		this(recipient, incident, NotificationKind.SYSTEM, message, createdAt);
	}

	public Notification(AppUser recipient, Incident incident, NotificationKind kind, String message, Instant createdAt) {
		this.recipient = recipient;
		this.incident = incident;
		this.kind = kind != null ? kind : NotificationKind.SYSTEM;
		this.message = message;
		this.createdAt = createdAt;
	}

	/** Marks this notification as read. Idempotent: a second call does not change the timestamp. */
	public void markRead(Instant now) {
		if (this.readAt == null) {
			this.readAt = now;
		}
	}

	public boolean isRead() {
		return readAt != null;
	}
}
