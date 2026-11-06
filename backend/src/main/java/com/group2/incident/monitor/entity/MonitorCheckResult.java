package com.group2.incident.monitor.entity;

import com.group2.incident.monitor.service.ProbeOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** One check of a monitor: every result is kept (FR-22.1). */
@Entity
@Table(name = "monitor_check_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonitorCheckResult {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Filled and filtered by Hibernate from OrgContext (D48); never set by hand.
	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "monitor_id", nullable = false, updatable = false)
	private UptimeMonitor monitor;

	@Column(name = "checked_at", nullable = false, updatable = false)
	private Instant checkedAt;

	@Column(nullable = false, updatable = false)
	private boolean success;

	@Column(name = "status_code", updatable = false)
	private Integer statusCode;

	@Column(name = "response_time_ms", updatable = false)
	private Long responseTimeMs;

	@Column(columnDefinition = "text", updatable = false)
	private String error;

	public MonitorCheckResult(UptimeMonitor monitor, Instant checkedAt, ProbeOutcome outcome) {
		this.monitor = monitor;
		this.checkedAt = checkedAt;
		this.success = outcome.success();
		this.statusCode = outcome.statusCode();
		this.responseTimeMs = outcome.responseTimeMs();
		this.error = outcome.error();
	}
}
