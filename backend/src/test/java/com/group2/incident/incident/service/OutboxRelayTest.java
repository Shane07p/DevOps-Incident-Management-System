package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.group2.incident.common.config.MessagingConfig;
import com.group2.incident.incident.entity.Outbox;
import com.group2.incident.incident.entity.OutboxStatus;
import com.group2.incident.incident.repository.OutboxRepository;
import com.group2.incident.support.IntegrationTest;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/** The outbox relay against a real RabbitMQ (#82): publish and consume, order, and an unconfirmed send. */
@IntegrationTest
@Testcontainers
class OutboxRelayTest {

	@Container
	static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.4.0"));

	@DynamicPropertySource
	static void rabbit(DynamicPropertyRegistry registry) {
		registry.add("spring.rabbitmq.addresses", RABBIT::getAmqpUrl);
		// The tests run each pass by hand; the scheduler waits an hour.
		registry.add("app.outbox.relay-delay-ms", () -> "3600000");
	}

	@Autowired
	private OutboxRelay relay;

	@Autowired
	private OutboxRepository outbox;

	@Autowired
	private AmqpAdmin admin;

	@Autowired
	private Clock clock;

	@MockitoSpyBean
	private RabbitTemplate rabbitTemplate;

	private Queue consumer;

	// A consumer's queue, bound to every incident event, as a real consumer would bind one. Durable and
	// not exclusive: RabbitMQ 4 refuses transient shared queues, and an exclusive one dies with its
	// connection, which the cached connections may replace.
	@BeforeEach
	void bindAConsumer() {
		TopicExchange exchange = new TopicExchange(MessagingConfig.EVENTS_EXCHANGE, true, false);
		admin.declareExchange(exchange);
		consumer = new Queue("test.incident-events." + UUID.randomUUID(), true, false, false);
		admin.declareQueue(consumer);
		admin.declareBinding(BindingBuilder.bind(consumer).to(exchange).with("incident.#"));
	}

	@AfterEach
	void deleteTheQueue() {
		admin.deleteQueue(consumer.getName());
	}

	@Test
	void aPendingEventIsPublishedConsumedAndMarkedSent() {
		Outbox event = outbox.saveAndFlush(new Outbox("INCIDENT", 7L, "INCIDENT_ESCALATED", "{\"incidentId\":7}", clock.instant()));

		assertThat(relay.relayOnce()).isEqualTo(1);

		Message received = rabbitTemplate.receive(consumer.getName(), 5_000);
		assertThat(received).isNotNull();
		assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"incidentId\":7}");
		assertThat(received.getMessageProperties().getReceivedRoutingKey()).isEqualTo("incident.escalated");
		assertThat(received.getMessageProperties().getMessageId()).isEqualTo("outbox-" + event.getId());
		assertThat(received.getMessageProperties().getContentType()).isEqualTo("application/json");

		Outbox after = outbox.findById(event.getId()).orElseThrow();
		assertThat(after.getStatus()).isEqualTo(OutboxStatus.SENT);
		assertThat(after.getProcessedAt()).isNotNull();
	}

	@Test
	void eventsLeaveOldestFirst() {
		Outbox older = outbox.saveAndFlush(new Outbox("INCIDENT", 1L, "INCIDENT_ESCALATED", "{\"n\":1}", clock.instant().minusSeconds(60)));
		Outbox newer = outbox.saveAndFlush(new Outbox("INCIDENT", 1L, "INCIDENT_ESCALATED_FALLBACK", "{\"n\":2}", clock.instant()));

		assertThat(relay.relayOnce()).isEqualTo(2);

		assertThat(rabbitTemplate.receive(consumer.getName(), 5_000).getMessageProperties().getMessageId()).isEqualTo("outbox-" + older.getId());
		assertThat(rabbitTemplate.receive(consumer.getName(), 5_000).getMessageProperties().getMessageId()).isEqualTo("outbox-" + newer.getId());
	}

	@Test
	void anEventTheBrokerDoesNotConfirmStaysPending() {
		Outbox event = outbox.saveAndFlush(new Outbox("INCIDENT", 9L, "INCIDENT_ESCALATED", "{\"incidentId\":9}", clock.instant()));
		doThrow(new AmqpConnectException(new ConnectException("RabbitMQ is down"))).when(rabbitTemplate).invoke(any());

		assertThat(relay.relayOnce()).isZero();

		Outbox after = outbox.findById(event.getId()).orElseThrow();
		assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(after.getProcessedAt()).isNull();
	}
}
