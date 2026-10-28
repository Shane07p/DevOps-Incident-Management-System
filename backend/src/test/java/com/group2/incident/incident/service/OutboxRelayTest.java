package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.incident.entity.Outbox;
import com.group2.incident.incident.repository.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

	private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");

	@Mock
	private OutboxRepository outboxRepository;

	private Clock fixedClock;
	private OutboxRelay outboxRelay;

	@BeforeEach
	void setUp() {
		fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
		outboxRelay = new OutboxRelay(outboxRepository, fixedClock);
	}

	@Test
	@DisplayName("OutboxRelay processes pending messages and marks them SENT with timestamp")
	void processesPendingMessages() {
		Outbox msg1 = new Outbox("INCIDENT", 101L, "INCIDENT_ESCALATED", "{\"step\":1}", T0);
		Outbox msg2 = new Outbox("INCIDENT", 102L, "INCIDENT_ESCALATED_FALLBACK", "{\"fallback\":true}", T0);

		when(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(msg1, msg2));

		int processed = outboxRelay.processPendingOutboxMessages();

		assertThat(processed).isEqualTo(2);
		assertThat(msg1.getStatus()).isEqualTo("SENT");
		assertThat(msg1.getProcessedAt()).isEqualTo(T0);
		assertThat(msg2.getStatus()).isEqualTo("SENT");
		assertThat(msg2.getProcessedAt()).isEqualTo(T0);
	}

	@Test
	@DisplayName("OutboxRelay returns 0 when no pending messages exist")
	void returnsZeroWhenEmpty() {
		when(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of());

		int processed = outboxRelay.processPendingOutboxMessages();

		assertThat(processed).isZero();
	}
}
