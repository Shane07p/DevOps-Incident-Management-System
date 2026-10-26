package com.group2.incident.incident.entity;

import com.group2.incident.auth.entity.AppUser;
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

@Entity
@Table(name = "timeline_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TimelineEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incident_id", nullable = false)
	private Incident incident;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "actor_id")
	private AppUser actor;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 50)
	private TimelineEventType eventType;

	@Column(nullable = false, columnDefinition = "text")
	private String details;

	@Column(name = "recorded_at", nullable = false, updatable = false)
	private Instant recordedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "corrects_entry_id")
	private TimelineEntry correctsEntry;

	public TimelineEntry(Incident incident, AppUser actor, TimelineEventType eventType,
			String details, Instant recordedAt) {
		this(incident, actor, eventType, details, recordedAt, null);
	}

	public TimelineEntry(Incident incident, AppUser actor, TimelineEventType eventType,
			String details, Instant recordedAt, TimelineEntry correctsEntry) {
		this.incident = incident;
		this.actor = actor;
		this.eventType = eventType;
		this.details = details;
		this.recordedAt = recordedAt;
		this.correctsEntry = correctsEntry;
	}
}
