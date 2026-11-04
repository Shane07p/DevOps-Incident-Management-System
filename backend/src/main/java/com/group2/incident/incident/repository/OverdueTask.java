package com.group2.incident.incident.repository;

/** An overdue escalation task and the org it belongs to, read across all orgs by the worker. */
public record OverdueTask(Long id, Long orgId) {
}
