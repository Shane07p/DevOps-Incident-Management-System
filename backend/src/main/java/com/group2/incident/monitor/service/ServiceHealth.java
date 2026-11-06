package com.group2.incident.monitor.service;

import com.group2.incident.monitor.entity.UptimeMonitor;

/**
 * A service's health on the service map, from its enabled monitors (FR-22, FEATURES "Service health").
 * A service with several monitors takes the worst of them. Listed from best known to worst.
 */
public enum ServiceHealth {

	/** No enabled monitor has been checked yet, or the service has none. */
	UNKNOWN,

	/** Every checked monitor passed its last check. */
	OPERATIONAL,

	/** A monitor failed its last check but has not reached its failure threshold. */
	DEGRADED,

	/** A monitor reached its failure threshold, which is when it raises its alert, and has not passed since. */
	DOWN;

	public static ServiceHealth of(UptimeMonitor monitor) {
		int failures = monitor.getConsecutiveFailures();
		if (failures >= monitor.getFailureThreshold()) {
			return DOWN;
		}
		return failures > 0 ? DEGRADED : OPERATIONAL;
	}

	public static ServiceHealth worse(ServiceHealth a, ServiceHealth b) {
		return a.ordinal() >= b.ordinal() ? a : b;
	}
}
