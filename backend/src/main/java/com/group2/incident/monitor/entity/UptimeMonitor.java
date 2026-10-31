
package com.group2.incident.monitor.entity;

import com.group2.incident.catalog.entity.ServiceEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "uptime_monitor")
@Getter
@NoArgsConstructor
public class UptimeMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "expected_status", nullable = false)
    private Integer expectedStatus = 200;

    @Column(name = "interval_seconds", nullable = false)
    private Integer intervalSeconds = 60;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceEntity service;

    @Column(name = "failure_threshold", nullable = false)
    private Integer failureThreshold = 3;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "consecutive_failures", nullable = false)
    private Integer consecutiveFailures = 0;

    @Column(name = "outage_started_at")
    private Instant outageStartedAt;

    @Column(name = "active_alert_id")
    private Long activeAlertId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void configure(
            String name,
            String url,
            Integer expectedStatus,
            Integer intervalSeconds,
            ServiceEntity service,
            Integer failureThreshold,
            boolean enabled,
            Instant now) {

        this.name = name;
        this.url = url;
        this.expectedStatus = expectedStatus;
        this.intervalSeconds = intervalSeconds;
        this.service = service;
        this.failureThreshold = failureThreshold;
        this.enabled = enabled;

        if (this.createdAt == null) {
            this.createdAt = now;
        }

        this.updatedAt = now;
    }

    public void recordFailure(Instant checkedAt) {
        if (consecutiveFailures == null || consecutiveFailures == 0) {
            outageStartedAt = checkedAt;
        }

        consecutiveFailures =
                (consecutiveFailures == null ? 0 : consecutiveFailures) + 1;

        updatedAt = checkedAt;
    }

    public void recordSuccess(Instant checkedAt) {
        consecutiveFailures = 0;
        updatedAt = checkedAt;
    }

    public void attachAlert(Long alertId, Instant now) {
        activeAlertId = alertId;
        updatedAt = now;
    }

    public void markRecovered(Instant recoveredAt) {
        consecutiveFailures = 0;
        activeAlertId = null;
        outageStartedAt = null;
        updatedAt = recoveredAt;
    }
}
