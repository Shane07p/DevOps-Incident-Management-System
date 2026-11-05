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
import com.group2.incident.monitor.repository.DueMonitor;
import com.group2.incident.monitor.repository.MonitorCheckResultRepository;
import com.group2.incident.monitor.repository.UptimeMonitorRepository;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Saves one check's result and acts on it, in a transaction of its own inside the monitor's org (D48). */
@Service
@RequiredArgsConstructor
public class MonitorCheckRecorder {

	/** The source of the alerts our monitors raise; with the event id, it is the duplicate guard. */
	public static final String ALERT_SOURCE = "uptime-monitor";

	private final UptimeMonitorRepository monitorRepository;
	private final MonitorCheckResultRepository resultRepository;
	private final AlertService alertService;
	private final AlertRepository alertRepository;
	private final TimelineEntryRepository timelineEntryRepository;

	/**
	 * Returns false, and saves nothing, when the monitor was edited, switched off, or removed while its
	 * check was in flight: the result belongs to settings that no longer apply. An edit that commits
	 * while this runs makes the commit fail on the monitor's version instead.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean record(DueMonitor due, ProbeOutcome outcome, Instant checkedAt) {
		UptimeMonitor monitor = monitorRepository.findWithServiceById(due.id()).orElse(null);
		if (monitor == null || monitor.getVersion() != due.version() || !monitor.isEnabled()) {
			return false;
		}
		resultRepository.save(new MonitorCheckResult(monitor, checkedAt, outcome));
		if (outcome.success()) {
			Long recoveredAlertId = monitor.recordSuccess(checkedAt);
			if (recoveredAlertId != null) {
				recover(monitor, recoveredAlertId, outcome, checkedAt);
			}
		}
		else if (monitor.recordFailure(checkedAt)) {
			// FR-22.1: the threshold is reached; the alert goes through intake like a tool's would.
			Alert alert = alertService.raiseMonitorAlert(alertFor(monitor, outcome));
			monitor.alertRaised(alert.getId());
		}
		return true;
	}

	/** FR-22.2 and FR-22.3: the alert records the recovery and the incident's timeline says so; nothing resolves. */
	private void recover(UptimeMonitor monitor, Long alertId, ProbeOutcome outcome, Instant recoveredAt) {
		alertRepository.findById(alertId).ifPresent(alert -> {
			alert.recordRecovery(recoveredAt);
			timelineEntryRepository.save(new TimelineEntry(
					alert.getIncident(),
					null,
					TimelineEventType.MONITOR_RECOVERED,
					"Uptime monitor " + monitor.getName() + " is passing again (HTTP " + outcome.statusCode()
							+ "). The incident stays open until someone resolves it.",
					recoveredAt
			));
		});
	}

	/**
	 * One event per outage, so a repeat is a duplicate rather than a second alert. The correlation key
	 * names the monitor, so a new outage joins the incident if that one is still open.
	 */
	private static AlertRequest alertFor(UptimeMonitor monitor, ProbeOutcome outcome) {
		return new AlertRequest(
				ALERT_SOURCE,
				"monitor-" + monitor.getId() + "-outage-" + monitor.getOutageStartedAt().toEpochMilli(),
				monitor.getService().getName(),
				"Uptime monitor failing: " + monitor.getName(),
				monitor.getUrl() + " failed " + monitor.getConsecutiveFailures() + " checks in a row. Last result: "
						+ outcome.error(),
				// Every monitor alert starts at SEV2; responders change it on the incident.
				Severity.SEV2.name(),
				"uptime-monitor-" + monitor.getId(),
				Map.of("monitorId", monitor.getId(), "url", monitor.getUrl())
		);
	}
}
