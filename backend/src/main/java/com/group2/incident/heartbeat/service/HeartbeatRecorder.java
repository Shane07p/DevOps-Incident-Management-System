package com.group2.incident.heartbeat.service;

import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.entity.Alert;
import com.group2.incident.alert.repository.AlertRepository;
import com.group2.incident.alert.service.AlertService;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.heartbeat.dto.HeartbeatPingResponse;
import com.group2.incident.heartbeat.entity.Heartbeat;
import com.group2.incident.heartbeat.repository.DueHeartbeat;
import com.group2.incident.heartbeat.repository.HeartbeatRepository;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Saves a heartbeat's overdue alert or ping recovery in its own transaction inside the heartbeat's org (D48). */
@Service
@RequiredArgsConstructor
public class HeartbeatRecorder {

	public static final String ALERT_SOURCE = "heartbeat";

	private final HeartbeatRepository heartbeatRepository;
	private final AlertService alertService;
	private final AlertRepository alertRepository;
	private final TimelineEntryRepository timelineEntryRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean recordOverdue(DueHeartbeat due, Instant now) {
		Heartbeat heartbeat = heartbeatRepository.findWithServiceById(due.id()).orElse(null);
		if (heartbeat == null || heartbeat.getVersion() != due.version() || !heartbeat.isEnabled() || heartbeat.getActiveAlertId() != null) {
			return false;
		}
		if (heartbeat.recordMissed(now)) {
			Alert alert = alertService.raiseMonitorAlert(alertFor(heartbeat, now));
			heartbeat.alertRaised(alert.getId());
		}
		return true;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public HeartbeatPingResponse recordPing(Long heartbeatId, Instant pingedAt) {
		Heartbeat heartbeat = heartbeatRepository.findWithServiceById(heartbeatId)
				.orElseThrow(() -> new NotFoundException("Heartbeat " + heartbeatId + " not found"));
		Long recoveredAlertId = heartbeat.recordPing(pingedAt);
		if (recoveredAlertId != null) {
			recover(heartbeat, recoveredAlertId, pingedAt);
		}
		return new HeartbeatPingResponse("ok", heartbeat.getName(), pingedAt);
	}

	private void recover(Heartbeat heartbeat, Long alertId, Instant recoveredAt) {
		alertRepository.findById(alertId).ifPresent(alert -> {
			alert.recordRecovery(recoveredAt);
			timelineEntryRepository.save(new TimelineEntry(
					alert.getIncident(),
					null,
					TimelineEventType.MONITOR_RECOVERED,
					"Heartbeat " + heartbeat.getName() + " is receiving pings again. The incident stays open until someone resolves it.",
					recoveredAt
			));
		});
	}

	private static AlertRequest alertFor(Heartbeat heartbeat, Instant now) {
		return new AlertRequest(
				ALERT_SOURCE,
				"heartbeat-" + heartbeat.getId() + "-missed-" + heartbeat.getOutageStartedAt().toEpochMilli(),
				heartbeat.getService().getName(),
				"Heartbeat missed: " + heartbeat.getName(),
				"Heartbeat " + heartbeat.getName() + " missed its scheduled ping. Expected every "
						+ heartbeat.getIntervalSeconds() + " s (grace " + heartbeat.getGraceSeconds() + " s).",
				Severity.SEV2.name(),
				"heartbeat-" + heartbeat.getId(),
				Map.of("heartbeatId", heartbeat.getId())
		);
	}
}
