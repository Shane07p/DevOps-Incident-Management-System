package com.group2.incident.incident.service;

import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.entity.EscalationTaskState;
import com.group2.incident.incident.repository.EscalationTaskRepository;
import com.group2.incident.incident.repository.OverdueTask;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-checks overdue escalation steps. Deadlines live in the database, so a restart catches up. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EscalationWorker {

	private final EscalationTaskRepository escalationTaskRepository;
	private final EscalationService escalationService;
	private final Clock clock;

	@Scheduled(fixedDelayString = "${app.escalation.worker-delay-ms:5000}")
	public void checkOverdueEscalations() {
		int processed = runOnce(clock.instant());
		if (processed > 0) {
			log.info("Escalation worker processed {} overdue escalation step(s)", processed);
		}
	}

	/**
	 * One pass over the tasks overdue at {@code now}. Each step runs through the service's proxy in
	 * its own transaction, so a lost race or a failure rolls back only that task.
	 */
	public int runOnce(Instant now) {
		int processed = 0;
		List<OverdueTask> overdue = OrgContext.runAsRoot(() -> escalationTaskRepository.findOverdueTasks(EscalationTaskState.PENDING, now));
		for (OverdueTask task : overdue) {
			try {
				// Each step opens its own transaction, inside the task's org (D48).
				if (OrgContext.runAs(task.orgId(), () -> escalationService.escalateTaskInNewTransaction(task.id(), now))) {
					processed++;
				}
			}
			catch (OptimisticLockingFailureException e) {
				log.info("Escalation task {} lost a race with another change to its incident; rolled back", task.id());
			}
			catch (RuntimeException e) {
				log.error("Failed to escalate task {}", task.id(), e);
			}
		}
		return processed;
	}
}
