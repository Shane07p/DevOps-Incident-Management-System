package com.group2.incident.incident.service;

import com.group2.incident.incident.entity.Outbox;
import com.group2.incident.incident.repository.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional Outbox Relay scheduler.
 * Reads pending outbox entries written during incident escalations and marks them sent.
 * Decoupled from live RabbitMQ dispatch until Sprint 4 queue hosting is wired.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

	private final OutboxRepository outboxRepository;
	private final Clock clock;

	@Scheduled(fixedDelayString = "${app.outbox.relay-delay-ms:5000}")
	@Transactional
	public int processPendingOutboxMessages() {
		List<Outbox> pending = outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING");
		if (pending.isEmpty()) {
			return 0;
		}

		Instant now = clock.instant();
		for (Outbox message : pending) {
			log.info("Outbox relay processing event [id={}, aggregate={}:{}, eventType={}]",
					message.getId(), message.getAggregateType(), message.getAggregateId(), message.getEventType());
			message.markSent(now);
		}
		return pending.size();
	}
}
