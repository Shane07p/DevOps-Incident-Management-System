
package com.group2.incident.monitor.service;

import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.entity.Alert;
import com.group2.incident.alert.repository.AlertRepository;
import com.group2.incident.alert.service.AlertService;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.monitor.entity.MonitorCheckResult;
import com.group2.incident.monitor.entity.UptimeMonitor;
import com.group2.incident.monitor.repository.MonitorCheckResultRepository;
import com.group2.incident.monitor.repository.UptimeMonitorRepository;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class MonitorCheckScheduler {

    private final UptimeMonitorRepository monitorRepository;
    private final MonitorCheckResultRepository resultRepository;
    private final AlertService alertService;
    private final AlertRepository alertRepository;
    private final TimelineEntryRepository timelineRepository;
    private final Clock clock;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Scheduled(fixedDelayString = "${monitor.checker.poll-delay-ms:5000}")
    @Transactional
    public void checkMonitors() {
        List<UptimeMonitor> monitors =
                monitorRepository.findAllByEnabledTrue();

        for (UptimeMonitor monitor : monitors) {
            try {
                checkIfDue(monitor);
            } catch (Exception exception) {
                log.error(
                        "Monitor check failed for monitor {}",
                        monitor.getId(),
                        exception
                );
            }
        }
    }

    private void checkIfDue(UptimeMonitor monitor) {
        Instant now = clock.instant();

        var latest = resultRepository
                .findFirstByMonitor_IdOrderByCheckedAtDescIdDesc(
                        monitor.getId()
                );

        if (latest.isPresent()
                && latest.get().getCheckedAt()
                .plusSeconds(monitor.getIntervalSeconds())
                .isAfter(now)) {
            return;
        }

        checkMonitor(monitor, now);
    }

    private void checkMonitor(
            UptimeMonitor monitor,
            Instant checkedAt) {

        Integer actualStatus = null;
        Long responseTime = null;
        String errorMessage = null;
        boolean success = false;

        long start = System.nanoTime();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(monitor.getUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<Void> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );

            actualStatus = response.statusCode();

            responseTime = Duration.ofNanos(
                    System.nanoTime() - start
            ).toMillis();

            success = actualStatus.equals(
                    monitor.getExpectedStatus()
            );

            if (!success) {
                errorMessage = "Expected HTTP "
                        + monitor.getExpectedStatus()
                        + " but received HTTP " + actualStatus;
            }

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            errorMessage = "Monitor check interrupted";

        } catch (IOException | IllegalArgumentException exception) {
            errorMessage = exception.getMessage();
        }

        resultRepository.save(new MonitorCheckResult(
                monitor,
                checkedAt,
                success,
                actualStatus,
                responseTime,
                errorMessage
        ));

        if (success) {
            handleRecovery(monitor, checkedAt);
        } else {
            handleFailure(
                    monitor,
                    checkedAt,
                    actualStatus,
                    errorMessage
            );
        }
    }

    private void handleFailure(
            UptimeMonitor monitor,
            Instant checkedAt,
            Integer actualStatus,
            String errorMessage) {

        monitor.recordFailure(checkedAt);

        if (monitor.getConsecutiveFailures()
                >= monitor.getFailureThreshold()
                && monitor.getActiveAlertId() == null) {

            String eventId = "monitor-" + monitor.getId()
                    + "-outage-"
                    + monitor.getOutageStartedAt().toEpochMilli();

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("monitorId", monitor.getId());
            details.put("monitorName", monitor.getName());
            details.put("url", monitor.getUrl());
            details.put("expectedStatus", monitor.getExpectedStatus());
            details.put("actualStatus", actualStatus);
            details.put("error", errorMessage);
            details.put(
                    "consecutiveFailures",
                    monitor.getConsecutiveFailures()
            );

            AlertRequest request = new AlertRequest(
                    "uptime-monitor",
                    eventId,
                    monitor.getService().getName(),
                    "Uptime monitor failed: " + monitor.getName(),
                    "Monitor " + monitor.getName()
                            + " failed "
                            + monitor.getConsecutiveFailures()
                            + " consecutive checks. "
                            + errorMessage,
                    Severity.SEV2.name(),
                    "uptime-monitor-" + monitor.getId(),
                    details
            );

            Alert alert = alertService.createMonitorAlert(request);

            monitor.attachAlert(alert.getId(), clock.instant());

            log.warn(
                    "Created uptime alert {} for monitor {}",
                    alert.getId(),
                    monitor.getId()
            );
        }

        monitorRepository.save(monitor);
    }

    private void handleRecovery(
            UptimeMonitor monitor,
            Instant recoveredAt) {

        if (monitor.getActiveAlertId() != null) {
            Alert alert = alertRepository
                    .findById(monitor.getActiveAlertId())
                    .orElse(null);

            if (alert != null) {
                alert.markRecovered(recoveredAt);
                alertRepository.save(alert);

                timelineRepository.save(new TimelineEntry(
                        alert.getIncident(),
                        null,
                        TimelineEventType.MONITOR_RECOVERED,
                        "Uptime monitor " + monitor.getName()
                                + " recovered at " + recoveredAt,
                        recoveredAt
                ));
            }
        }

        monitor.markRecovered(recoveredAt);
        monitorRepository.save(monitor);
    }
}
