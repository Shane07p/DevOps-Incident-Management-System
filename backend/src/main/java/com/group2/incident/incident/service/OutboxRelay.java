package com.group2.incident.incident.service;

import com.group2.incident.common.config.MessagingConfig;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.entity.Outbox;
import com.group2.incident.incident.entity.OutboxStatus;
import com.group2.incident.incident.repository.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends PENDING outbox events to RabbitMQ, oldest first (D24, #82). An event is marked SENT only after
 * the broker confirms it, so it can be delivered twice but never lost: consumers dedupe by message id.
 * Runs only when {@code RABBITMQ_URL} is set; otherwise the events wait in the outbox.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("!'${spring.rabbitmq.addresses:}'.isBlank()")
public class OutboxRelay {

	/** Message header with the id of the org the event belongs to (D48). */
	public static final String ORG_HEADER = "orgId";

	private static final long CONFIRM_TIMEOUT_MS = 5_000;

	private final OutboxRepository outboxRepository;
	private final RabbitTemplate rabbitTemplate;
	private final Clock clock;

	@Scheduled(initialDelayString = "${app.outbox.relay-delay-ms}", fixedDelayString = "${app.outbox.relay-delay-ms}")
	public void relayPending() {
		int sent = relayOnce();
		if (sent > 0) {
			log.info("Outbox relay sent {} event(s) to RabbitMQ", sent);
		}
	}

	/** One pass: sends what is PENDING until the broker refuses one, which waits for the next pass. */
	public int relayOnce() {
		// The outbox of every org (D48); each message says which org it belongs to.
		return OrgContext.runAsRoot(this::relayPendingEvents);
	}

	private int relayPendingEvents() {
		int sent = 0;
		for (Outbox event : outboxRepository.findTop50ByStatusOrderByCreatedAtAscIdAsc(OutboxStatus.PENDING)) {
			try {
				rabbitTemplate.invoke(operations -> {
					operations.send(MessagingConfig.EVENTS_EXCHANGE, routingKey(event.getEventType()), toMessage(event));
					operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
					return null;
				});
			}
			catch (AmqpException e) {
				log.warn("RabbitMQ did not confirm outbox event {}; it stays PENDING until the next pass: {}", event.getId(), e.getMessage());
				return sent;
			}
			outboxRepository.markSent(event.getId(), clock.instant());
			sent++;
		}
		return sent;
	}

	/** INCIDENT_ESCALATED becomes incident.escalated, so consumers can bind to incident.#. */
	static String routingKey(String eventType) {
		return eventType.toLowerCase(Locale.ROOT).replace('_', '.');
	}

	private static Message toMessage(Outbox event) {
		return MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.setContentEncoding(StandardCharsets.UTF_8.name())
				.setMessageId("outbox-" + event.getId())
				.setType(event.getEventType())
				.setHeader(ORG_HEADER, event.getOrgId())
				.setTimestamp(Date.from(event.getCreatedAt()))
				.build();
	}
}
