package com.group2.incident.alert.entity;

import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
		name = "alert",
		uniqueConstraints = @UniqueConstraint(columnNames = { "source", "event_id" })
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Alert {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String source;

	@Column(name = "event_id", nullable = false, length = 200)
	private String eventId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "service_id", nullable = false)
	private ServiceEntity service;

	@Column(name = "correlation_key", length = 200)
	private String correlationKey;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incident_id", nullable = false)
	private Incident incident;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Severity severity;

	@Column(nullable = false, columnDefinition = "text")
	private String payload;

	@Column(name = "payload_fingerprint", nullable = false, length = 64)
	private String payloadFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AlertStatus status;

	@Column(name = "received_at", nullable = false)
	private Instant receivedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
@Column(name = "recovered_at")
private Instant recoveredAt;

public void markRecovered(Instant recoveredAt) {
    this.recoveredAt = recoveredAt;
}
	public Alert(
			String source,
			String eventId,
			ServiceEntity service,
			String correlationKey,
			Incident incident,
			String title,
			String description,
			Severity severity,
			String payload,
			String payloadFingerprint,
			AlertStatus status,
			Instant receivedAt
	) {
		this.source = source;
		this.eventId = eventId;
		this.service = service;
		this.correlationKey = correlationKey;
		this.incident = incident;
		this.title = title;
		this.description = description;
		this.severity = severity;
		this.payload = payload;
		this.payloadFingerprint = payloadFingerprint;
		this.status = status;
		this.receivedAt = receivedAt;
		this.createdAt = receivedAt;
	}
}
