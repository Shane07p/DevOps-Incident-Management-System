package com.group2.incident.incident.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EscalationWorker {

	private final EscalationService escalationService;

	@Scheduled(fixedDelayString = "${app.escalation.worker-delay-ms:5000}")
	public void checkOverdueEscalations() {
		int processed = escalationService.processOverdueEscalations();
		if (processed > 0) {
			log.info("Escalation worker processed {} overdue escalation step(s)", processed);
		}
	}
}
