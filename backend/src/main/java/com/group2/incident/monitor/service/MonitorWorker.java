package com.group2.incident.monitor.service;

import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.monitor.repository.DueMonitor;
import com.group2.incident.monitor.repository.UptimeMonitorRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the uptime checks that are due (FR-22). The schedule lives in the database, so a restart catches up. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorWorker {

	private final UptimeMonitorRepository monitorRepository;
	private final HttpProbe probe;
	private final MonitorCheckRecorder recorder;
	private final Clock clock;

	// Off in tests, which call runOnce with the time they choose.
	@Value("${app.monitors.worker-enabled:true}")
	private boolean enabled;

	@Scheduled(fixedDelayString = "${app.monitors.worker-delay-ms:10000}")
	public void checkDueMonitors() {
		if (enabled) {
			runOnce(clock.instant());
		}
	}

	/**
	 * One pass over the monitors due at {@code now}. The HTTP call runs outside any transaction, and
	 * each result is saved in its own, so a slow site, a failure, or an edit affects only that monitor.
	 */
	public int runOnce(Instant now) {
		List<DueMonitor> due = OrgContext.runAsRoot(() -> monitorRepository.findDue(now));
		int recorded = 0;
		for (DueMonitor monitor : due) {
			try {
				ProbeOutcome outcome = probe.probe(monitor.url(), monitor.expectedStatus());
				if (OrgContext.runAs(monitor.orgId(), () -> recorder.record(monitor, outcome, now))) {
					recorded++;
				}
			}
			catch (OptimisticLockingFailureException e) {
				log.info("Monitor {} was edited during its check; the result was dropped", monitor.id());
			}
			catch (RuntimeException e) {
				log.error("Failed to record the check of monitor {}", monitor.id(), e);
			}
		}
		return recorded;
	}
}
