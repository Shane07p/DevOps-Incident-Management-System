package com.settle.event.service;

import com.settle.event.dto.IncidentChangedNotification;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory registry of server-sent event emitters per organization (D46).
 * Delivers incident change events only to subscribers of the matching org.
 */
@Slf4j
@Service
public class EventStreamService {

	private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
	private static final int MAX_STREAMS_PER_USER = 5;
	private final Map<Subscriber, Deque<SseEmitter>> subscriptions = new HashMap<>();

	public SseEmitter subscribe(Long orgId, Long userId) {
		return subscribe(orgId, userId, new SseEmitter(STREAM_TIMEOUT_MS));
	}

	public SseEmitter subscribe(Long orgId, Long userId, SseEmitter emitter) {
		Subscriber subscriber = new Subscriber(orgId, userId);
		emitter.onCompletion(() -> remove(subscriber, emitter));
		emitter.onTimeout(() -> remove(subscriber, emitter));
		emitter.onError(e -> remove(subscriber, emitter));
		SseEmitter oldest = register(subscriber, emitter);
		if (oldest != null) oldest.complete();
		send(new Stream(subscriber, emitter), SseEmitter.event().comment("connected"));
		return emitter;
	}

	public void broadcastIncidentChanged(Long orgId, Long incidentId) {
		for (Stream stream : snapshot(orgId)) {
			send(stream, SseEmitter.event().name("incident-changed")
					.data(new IncidentChangedNotification(incidentId)));
		}
	}

	@Scheduled(fixedDelay = 25_000)
	public void heartbeat() {
		for (Stream stream : snapshot(null)) send(stream, SseEmitter.event().comment(" ping"));
	}

	public synchronized int getSubscriberCount(Long orgId) {
		return subscriptions.entrySet().stream().filter(e -> e.getKey().orgId().equals(orgId))
				.mapToInt(e -> e.getValue().size()).sum();
	}

	private synchronized SseEmitter register(Subscriber subscriber, SseEmitter emitter) {
		Deque<SseEmitter> emitters = subscriptions.computeIfAbsent(subscriber, key -> new ArrayDeque<>());
		emitters.addLast(emitter);
		return emitters.size() > MAX_STREAMS_PER_USER ? emitters.removeFirst() : null;
	}

	private synchronized void remove(Subscriber subscriber, SseEmitter emitter) {
		Deque<SseEmitter> emitters = subscriptions.get(subscriber);
		if (emitters != null) {
			emitters.remove(emitter);
			if (emitters.isEmpty()) subscriptions.remove(subscriber);
		}
	}

	private synchronized List<Stream> snapshot(Long orgId) {
		List<Stream> streams = new ArrayList<>();
		subscriptions.forEach((subscriber, emitters) -> {
			if (orgId == null || subscriber.orgId().equals(orgId)) {
				for (SseEmitter emitter : emitters) streams.add(new Stream(subscriber, emitter));
			}
		});
		return streams;
	}

	private void send(Stream stream, SseEmitter.SseEventBuilder event) {
		try {
			stream.emitter().send(event);
		}
		catch (IOException | IllegalStateException failure) {
			remove(stream.subscriber(), stream.emitter());
			stream.emitter().complete();
			log.debug("Removed failed SSE stream in org {}", stream.subscriber().orgId(), failure);
		}
	}

	private record Subscriber(Long orgId, Long userId) { }
	private record Stream(Subscriber subscriber, SseEmitter emitter) { }
}
