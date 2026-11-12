package com.group2.incident.monitor.entity;

import com.group2.incident.catalog.entity.ServiceEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** An HTTP check of one service (FR-22). Admins change its settings; the checker records its results. */
@Entity
@Table(name = "uptime_monitor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UptimeMonitor {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Filled and filtered by Hibernate from OrgContext (D48); never set by hand.
	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "service_id", nullable = false)
	private ServiceEntity service;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 2048)
	private String url;

	@Column(name = "expected_status", nullable = false)
	private int expectedStatus;

	@Column(name = "interval_seconds", nullable = false)
	private int intervalSeconds;

	@Column(name = "failure_threshold", nullable = false)
	private int failureThreshold;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "next_check_at", nullable = false)
	private Instant nextCheckAt;

	@Column(name = "last_checked_at")
	private Instant lastCheckedAt;

	@Column(name = "consecutive_failures", nullable = false)
	private int consecutiveFailures;

	@Column(name = "outage_started_at")
	private Instant outageStartedAt;

	// The alert raised for the current outage; cleared when the monitor passes again.
	@Column(name = "active_alert_id")
	private Long activeAlertId;

	// An edit saved while a check is in flight changes this, so that check's result is dropped.
	@Version
	@Column(nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public UptimeMonitor(ServiceEntity service, String name, String url, int expectedStatus, int intervalSeconds,
			int failureThreshold, boolean enabled, Instant now) {
		apply(service, name, url, expectedStatus, intervalSeconds, failureThreshold, enabled, now);
		this.createdAt = now;
	}

	/**
	 * An Admin's edit. The next check runs at once, against the new settings. Pointing the monitor at
	 * another service or address starts a new observation: the failure count, outage, and alert start
	 * afresh, so the new service gets an alert of its own, and its first pass cannot recover the old
	 * service's alert. That alert stays as it was, for a person to resolve.
	 */
	public void change(ServiceEntity service, String name, String url, int expectedStatus, int intervalSeconds,
			int failureThreshold, boolean enabled, Instant now) {
		if (!this.service.getId().equals(service.getId()) || !this.url.equals(url)) {
			consecutiveFailures = 0;
			outageStartedAt = null;
			activeAlertId = null;
			lastCheckedAt = null;
		}
		apply(service, name, url, expectedStatus, intervalSeconds, failureThreshold, enabled, now);
	}

	/**
	 * A failed check. Returns true when this failure reaches the threshold and the outage has no alert
	 * yet, so the caller raises exactly one (FR-22.1).
	 */
	public boolean recordFailure(Instant checkedAt) {
		if (consecutiveFailures == 0) {
			outageStartedAt = checkedAt;
		}
		consecutiveFailures++;
		lastCheckedAt = checkedAt;
		nextCheckAt = checkedAt.plusSeconds(intervalSeconds);
		return consecutiveFailures >= failureThreshold && activeAlertId == null;
	}

	public void alertRaised(Long alertId) {
		this.activeAlertId = alertId;
	}

	/**
	 * A passing check: the count of failures starts again. Returns the id of the alert this ends, or null
	 * when there was no alert to recover (FR-22.2).
	 */
	public Long recordSuccess(Instant checkedAt) {
		Long recovered = activeAlertId;
		consecutiveFailures = 0;
		outageStartedAt = null;
		activeAlertId = null;
		lastCheckedAt = checkedAt;
		nextCheckAt = checkedAt.plusSeconds(intervalSeconds);
		return recovered;
	}

	private void apply(ServiceEntity service, String name, String url, int expectedStatus, int intervalSeconds,
			int failureThreshold, boolean enabled, Instant now) {
		this.service = service;
		this.name = name;
		this.url = url;
		this.expectedStatus = expectedStatus;
		this.intervalSeconds = intervalSeconds;
		this.failureThreshold = failureThreshold;
		this.enabled = enabled;
		this.nextCheckAt = now;
		this.updatedAt = now;
	}
}
