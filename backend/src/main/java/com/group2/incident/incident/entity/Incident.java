package com.group2.incident.incident.entity;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "incident")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String description;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "service_id", nullable = false)
	private ServiceEntity service;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Severity severity;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private IncidentStatus status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reporter_id")
	private AppUser reporter;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "assignee_id")
	private AppUser assignee;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "response_target_minutes", nullable = false, updatable = false)
	private int responseTargetMinutes;

	@Column(name = "resolution_target_minutes", nullable = false, updatable = false)
	private int resolutionTargetMinutes;

	@Version
	@Column(nullable = false)
	private Long version;

	public Incident(String title, String description, ServiceEntity service, Severity severity,
			IncidentStatus status, AppUser reporter, Instant createdAt,
			int responseTargetMinutes, int resolutionTargetMinutes) {
		this.title = title;
		this.description = description;
		this.service = service;
		this.severity = severity;
		this.status = status;
		this.reporter = reporter;
		this.createdAt = createdAt;
		this.responseTargetMinutes = responseTargetMinutes;
		this.resolutionTargetMinutes = resolutionTargetMinutes;
	}

	public void assignTo(AppUser assignee) {
		this.assignee = assignee;
	}

	public void acknowledge(AppUser acknowledger) {
		this.status = IncidentStatus.ACKNOWLEDGED;
		if (acknowledger != null) {
			this.assignee = acknowledger;
		}
	}

	public String getReference() {
		return "INC-" + id;
	}

	public Instant getResponseDueAt() {
		return createdAt.plus(responseTargetMinutes, ChronoUnit.MINUTES);
	}

	public Instant getResolutionDueAt() {
		return createdAt.plus(resolutionTargetMinutes, ChronoUnit.MINUTES);
	}
}
