package com.group2.incident.heartbeat.dto;

import java.time.Instant;

public record HeartbeatPingResponse(String status, String name, Instant receivedAt) {
}
