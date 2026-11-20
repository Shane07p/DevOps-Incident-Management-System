package com.group2.incident.event.service;

import com.group2.incident.event.model.IncidentChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for incident changes and broadcasts them to SSE subscribers after database commit.
 * Rolled back transactions do not trigger this listener.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentChangeEventListener {

	private final EventStreamService eventStreamService;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onIncidentChanged(IncidentChangeEvent event) {
		if (event != null && event.orgId() != null && event.incidentId() != null) {
			eventStreamService.broadcastIncidentChanged(event.orgId(), event.incidentId());
		}
	}
}
