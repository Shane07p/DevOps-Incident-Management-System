package com.group2.incident.heartbeat.entity;

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

/** A scheduled job's heartbeat check per service (FR-22). Jobs ping its URL; missed pings raise alerts. */
@Entity
@Table(name = "heartbeat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Heartbeat {

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

	@Column(name = "interval_seconds", nullable = false)
	private int intervalSeconds;

	@Column(name = "grace_seconds", nullable = false)
	private int graceSeconds;

	@Column(nullable = false, length = 64, unique = true)
	private String token;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "next_due_at", nullable = false)
	private Instant nextDueAt;

	@Column(name = "late_after", nullable = false)
	private Instant lateAfter;

	@Column(name = "last_ping_at")
	private Instant lastPingAt;

	@Column(name = "outage_started_at")
	private Instant outageStartedAt;

	// The alert raised for the current missed heartbeat; cleared when a ping arrives.
	@Column(name = "active_alert_id")
	private Long activeAlertId;

	@Version
	@Column(nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public Heartbeat(ServiceEntity service, String name, int intervalSeconds, int graceSeconds,
			String token, boolean enabled, Instant now) {
		this.service = service;
		this.name = name;
		this.intervalSeconds = intervalSeconds;
		this.graceSeconds = graceSeconds;
		this.token = token;
		this.enabled = enabled;
		this.createdAt = now;
		this.updatedAt = now;
		this.nextDueAt = now.plusSeconds(intervalSeconds);
		this.lateAfter = this.nextDueAt.plusSeconds(graceSeconds);
	}

	/**
	 * An Admin's edit. Pointing the heartbeat at another service resets the outage and alert.
	 */
	public void change(ServiceEntity service, String name, int intervalSeconds, int graceSeconds,
			boolean enabled, Instant now) {
		if (!this.service.getId().equals(service.getId())) {
			this.outageStartedAt = null;
			this.activeAlertId = null;
			this.lastPingAt = null;
			this.nextDueAt = now.plusSeconds(intervalSeconds);
			this.lateAfter = this.nextDueAt.plusSeconds(graceSeconds);
		}
		else if (this.intervalSeconds != intervalSeconds || this.graceSeconds != graceSeconds) {
			Instant base = this.lastPingAt != null ? this.lastPingAt : now;
			this.nextDueAt = base.plusSeconds(intervalSeconds);
			this.lateAfter = this.nextDueAt.plusSeconds(graceSeconds);
		}
		this.service = service;
		this.name = name;
		this.intervalSeconds = intervalSeconds;
		this.graceSeconds = graceSeconds;
		this.enabled = enabled;
		this.updatedAt = now;
	}

	/**
	 * A ping received from the job. Returns the id of the alert this resolves, or null when there was no active alert.
	 */
	public Long recordPing(Instant pingedAt) {
		Long recovered = this.activeAlertId;
		this.lastPingAt = pingedAt;
		this.nextDueAt = pingedAt.plusSeconds(intervalSeconds);
		this.lateAfter = this.nextDueAt.plusSeconds(graceSeconds);
		this.outageStartedAt = null;
		this.activeAlertId = null;
		this.updatedAt = pingedAt;
		return recovered;
	}

	/**
	 * Records that the heartbeat was missed at {@code missedAt}. Returns true if an alert should be raised.
	 */
	public boolean recordMissed(Instant missedAt) {
		if (this.activeAlertId != null) {
			return false;
		}
		this.outageStartedAt = missedAt;
		this.updatedAt = missedAt;
		return true;
	}

	public void alertRaised(Long alertId) {
		this.activeAlertId = alertId;
	}
}
