package com.settle.event.service;

import com.settle.event.model.IncidentChangeEvent;
import com.settle.incident.entity.TimelineEntry;
import jakarta.persistence.PostPersist;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes within the writer's transaction; delivery remains AFTER_COMMIT. */
@Component
@RequiredArgsConstructor
public class TimelineEntryListener {

	private final ApplicationEventPublisher publisher;

	@PostPersist
	public void persisted(TimelineEntry entry) {
		publisher.publishEvent(new IncidentChangeEvent(entry.getOrgId(), entry.getIncident().getId()));
	}
}
