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
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "incident")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Filled and filtered by Hibernate from OrgContext (D48); never set by hand.
	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@Column(nullable = false, updatable = false)
	private Long number;

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

	// The Lead is the assignee (D39); Comms and Ops are held separately.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "comms_id")
	private AppUser comms;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "ops_id")
	private AppUser ops;

	@Column(name = "correlation_source", length = 100)
	private String correlationSource;

	@Column(name = "correlation_key", length = 200)
	private String correlationKey;

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
		this(title, description, service, severity, status, reporter, createdAt,
				responseTargetMinutes, resolutionTargetMinutes, null, null);
	}

	public Incident(String title, String description, ServiceEntity service, Severity severity,
			IncidentStatus status, AppUser reporter, Instant createdAt,
			int responseTargetMinutes, int resolutionTargetMinutes,
			String correlationSource, String correlationKey) {
		this.title = title;
		this.description = description;
		this.service = service;
		this.severity = severity;
		this.status = status;
		this.reporter = reporter;
		this.createdAt = createdAt;
		this.responseTargetMinutes = responseTargetMinutes;
		this.resolutionTargetMinutes = resolutionTargetMinutes;
		this.correlationSource = correlationSource;
		this.correlationKey = correlationKey;
	}

	public void assignTo(AppUser assignee) {
		this.assignee = assignee;
	}

	/** Gives Comms or Ops to a person. The Lead is the assignee, changed only through {@link #assignTo}. */
	public void assignRole(IncidentRole role, AppUser user) {
		switch (role) {
			case LEAD -> throw new IllegalArgumentException("The Lead is the assignee; use assignTo");
			case COMMS -> this.comms = user;
			case OPS -> this.ops = user;
		}
	}

	public AppUser getRoleUser(IncidentRole role) {
		return switch (role) {
			case LEAD -> this.assignee;
			case COMMS -> this.comms;
			case OPS -> this.ops;
		};
	}

	/** Called once, right after construction, with the org's next number (IncidentService). */
	public void assignNumber(long number) {
		if (this.number != null) {
			throw new IllegalStateException("Incident " + this.number + " already has a number");
		}
		this.number = number;
	}

	public String getReference() {
		return reference(number);
	}

	/** How users see an incident's number, for code that has the number but not the entity (search rows). */
	public static String reference(Long number) {
		return "INC-" + number;
	}

	public Instant getResponseDueAt() {
		return createdAt.plus(responseTargetMinutes, ChronoUnit.MINUTES);
	}

	public Instant getResolutionDueAt() {
		return createdAt.plus(resolutionTargetMinutes, ChronoUnit.MINUTES);
	}
}
