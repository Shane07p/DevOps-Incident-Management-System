package com.settle.heartbeat.repository;

import java.time.Instant;

public record DueHeartbeat(Long id, Long orgId, long version, Instant lateAfter) {
}
