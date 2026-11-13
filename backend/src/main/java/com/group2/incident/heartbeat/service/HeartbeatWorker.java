package com.group2.incident.heartbeat.service;

import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.heartbeat.repository.DueHeartbeat;
import com.group2.incident.heartbeat.repository.HeartbeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs overdue heartbeat checks (FR-22). The schedule lives in the database, so a restart catches up. */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatWorker {

	private final HeartbeatRepository heartbeatRepository;
	private final HeartbeatRecorder recorder;
	private final Clock clock;

	// Off in tests, which call runOnce with the time they choose.
	@Value("${app.heartbeats.worker-enabled:true}")
	private boolean enabled;

	@Scheduled(fixedDelayString = "${app.heartbeats.worker-delay-ms:10000}")
	public void checkOverdueHeartbeats() {
		if (enabled) {
			runOnce(clock.instant());
		}
	}

	/**
	 * One pass over the heartbeats overdue at {@code now}. Each check runs in its own transaction
	 * inside the heartbeat's org, so a failure affects only that heartbeat.
	 */
	public int runOnce(Instant now) {
		List<DueHeartbeat> overdue = OrgContext.runAsRoot(() -> heartbeatRepository.findOverdue(now));
		int recorded = 0;
		for (DueHeartbeat due : overdue) {
			try {
				if (OrgContext.runAs(due.orgId(), () -> recorder.recordOverdue(due, now))) {
					recorded++;
				}
			}
			catch (OptimisticLockingFailureException e) {
				log.info("Heartbeat {} was modified during check; skipping", due.id());
			}
			catch (RuntimeException e) {
				log.error("Failed to record overdue heartbeat {}", due.id(), e);
			}
		}
		return recorded;
	}
}
