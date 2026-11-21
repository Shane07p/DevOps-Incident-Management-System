package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.common.config.MessagingConfig;
import com.group2.incident.incident.entity.Outbox;
import com.group2.incident.incident.entity.OutboxStatus;
import com.group2.incident.incident.repository.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The relay's answer to each way a publish can fail: the event stays PENDING and the pass stops, so order holds.
 * Publishing to a real broker is in {@code OutboxRelayTest}.
 */
class OutboxRelayPublishTest {

	private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

	private final OutboxRepository outbox = mock(OutboxRepository.class);

	private final RabbitTemplate rabbit = mock(RabbitTemplate.class);

	private final OutboxRelay relay = new OutboxRelay(outbox, rabbit, Clock.fixed(NOW, ZoneOffset.UTC));

	@BeforeEach
	void twoPendingEvents() {
		when(outbox.findTop50ByStatusOrderByCreatedAtAscIdAsc(OutboxStatus.PENDING)).thenReturn(List.of(event(1L), event(2L)));
	}

	@Test
	void aConfirmedEventIsMarkedSentAndTheScheduledPassLogsTheCount() {
		answerEachPublishWith(new CorrelationData.Confirm(true, null));

		relay.relayPending();

		verify(outbox).markSent(1L, NOW);
		verify(outbox).markSent(2L, NOW);
	}

	@Test
	void anEventTheBrokerRefusesStaysPendingAndStopsThePass() {
		answerEachPublishWith(new CorrelationData.Confirm(false, "queue full"));

		assertThat(relay.relayOnce()).isZero();

		verify(outbox, never()).markSent(anyLong(), any());
		verify(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
	}

	@Test
	void aBrokerThatCannotBeReachedLeavesTheEventPending() {
		doThrow(new AmqpConnectException(new java.net.ConnectException()))
				.when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

		assertThat(relay.relayOnce()).isZero();

		verify(outbox, never()).markSent(anyLong(), any());
	}

	// The stub never confirms, so the relay is still waiting when it sees the interrupt.
	@Test
	void anInterruptedWaitLeavesTheEventPendingAndKeepsTheInterrupt() {
		Thread.currentThread().interrupt();
		try {
			assertThat(relay.relayOnce()).isZero();
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}

		verify(outbox, never()).markSent(anyLong(), any());
	}

	private void answerEachPublishWith(CorrelationData.Confirm confirm) {
		doAnswer(inv -> {
			CorrelationData correlation = inv.getArgument(3);
			correlation.getFuture().complete(confirm);
			return null;
		}).when(rabbit).send(eq(MessagingConfig.EVENTS_EXCHANGE), anyString(), any(Message.class), any(CorrelationData.class));
	}

	private static Outbox event(long id) {
		Outbox event = new Outbox("INCIDENT", 7L, "INCIDENT_ESCALATED", "{\"incidentId\":7}", NOW.minusSeconds(60));
		ReflectionTestUtils.setField(event, "id", id);
		ReflectionTestUtils.setField(event, "orgId", 1L);
		return event;
	}
}
