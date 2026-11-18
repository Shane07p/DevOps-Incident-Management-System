package com.group2.incident.event.service;

import com.group2.incident.event.dto.IncidentChangedNotification;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory registry of server-sent event emitters per organization (D46).
 * Delivers incident change events only to subscribers of the matching org.
 */
@Slf4j
@Service
public class EventStreamService {

	private final Map<Long, Set<SseEmitter>> subscriptions = new ConcurrentHashMap<>();

	public SseEmitter subscribe(Long orgId) {
		return subscribe(orgId, new SseEmitter(0L));
	}

	public SseEmitter subscribe(Long orgId, SseEmitter emitter) {
		subscriptions.computeIfAbsent(orgId, k -> ConcurrentHashMap.newKeySet()).add(emitter);

		emitter.onCompletion(() -> remove(orgId, emitter));
		emitter.onTimeout(() -> remove(orgId, emitter));
		emitter.onError(e -> remove(orgId, emitter));

		try {
			emitter.send(SseEmitter.event().comment("connected"));
		}
		catch (IOException e) {
			log.debug("Initial SSE connection send failed for org {}", orgId, e);
			remove(orgId, emitter);
		}

		return emitter;
	}

	public void broadcastIncidentChanged(Long orgId, Long incidentId) {
		Set<SseEmitter> emitters = subscriptions.get(orgId);
		if (emitters == null || emitters.isEmpty()) {
			return;
		}

		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event()
						.name("incident-changed")
						.data(new IncidentChangedNotification(incidentId)));
			}
			catch (IOException | IllegalStateException ex) {
				log.debug("Failed to deliver SSE event to subscriber in org {}, removing emitter", orgId, ex);
				remove(orgId, emitter);
			}
		}
	}

	public int getSubscriberCount(Long orgId) {
		return subscriptions.getOrDefault(orgId, Collections.emptySet()).size();
	}

	private void remove(Long orgId, SseEmitter emitter) {
		Set<SseEmitter> emitters = subscriptions.get(orgId);
		if (emitters != null) {
			emitters.remove(emitter);
			if (emitters.isEmpty()) {
				subscriptions.remove(orgId);
			}
		}
	}
}
