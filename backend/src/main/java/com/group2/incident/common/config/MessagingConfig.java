package com.group2.incident.common.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The exchange our events are published to (D24, #82). Consumers bind their own queues to it by
 * routing key, such as {@code incident.escalated}. It is declared on the first connection, so nothing
 * happens while {@code RABBITMQ_URL} is unset.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingConfig {

	public static final String EVENTS_EXCHANGE = "settle.events";

	@Bean
	TopicExchange eventsExchange() {
		return new TopicExchange(EVENTS_EXCHANGE, true, false);
	}
}
