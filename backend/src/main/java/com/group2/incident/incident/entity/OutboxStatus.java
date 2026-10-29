package com.group2.incident.incident.entity;

/** Events stay PENDING until a publisher sends them to RabbitMQ (#82). */
public enum OutboxStatus {
	PENDING,
	SENT,
	FAILED
}
