package com.group2.incident.monitor.repository;

/**
 * A monitor due for a check, with what the check needs. Read across all orgs by the worker; the
 * version tells the recorder whether the monitor was edited while its check was in flight.
 */
public record DueMonitor(Long id, Long orgId, Long version, String url, Integer expectedStatus) {
}
