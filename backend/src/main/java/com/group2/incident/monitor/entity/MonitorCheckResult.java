
package com.group2.incident.monitor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(
    name = "monitor_check_result",
    indexes = {
        @Index(
            name = "monitor_check_result_history_idx",
            columnList = "monitor_id, checked_at, id"
        )
    }
)
@Getter
@NoArgsConstructor
public class MonitorCheckResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_id", nullable = false)
    private UptimeMonitor monitor;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "actual_status")
    private Integer actualStatus;

    @Column(name = "response_time_ms")
    private Long responseTimeMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    public MonitorCheckResult(
            UptimeMonitor monitor,
            Instant checkedAt,
            boolean success,
            Integer actualStatus,
            Long responseTimeMs,
            String errorMessage) {

        this.monitor = monitor;
        this.checkedAt = checkedAt;
        this.success = success;
        this.actualStatus = actualStatus;
        this.responseTimeMs = responseTimeMs;
        this.errorMessage = errorMessage;
    }
}
