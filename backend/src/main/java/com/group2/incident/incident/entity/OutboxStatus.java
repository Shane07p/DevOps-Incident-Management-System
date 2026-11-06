package com.group2.incident.incident.entity;

/** PENDING until `OutboxRelay` has RabbitMQ's confirmation, then SENT. With no RabbitMQ configured they stay PENDING. */
public enum OutboxStatus {
	PENDING,
	SENT,
	FAILED
}
