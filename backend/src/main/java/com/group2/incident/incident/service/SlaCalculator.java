package com.group2.incident.incident.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Calculates continuous elapsed time and SLA target breaches (FR-16).
 *
 * Rules:
 * - Exceeding the target is a breach.
 * - Reaching it exactly is NOT yet a breach.
 * - All continuous time counts (no pauses, night/weekend calendar deductions).
 */
public final class SlaCalculator {

	private SlaCalculator() {
	}

	public static boolean isBreached(Instant start, Instant stopOrNow, int targetMinutes) {
		Duration elapsed = Duration.between(start, stopOrNow);
		Duration target = Duration.ofMinutes(targetMinutes);
		return elapsed.compareTo(target) > 0;
	}

	public static Instant dueAt(Instant start, int targetMinutes) {
		return start.plus(Duration.ofMinutes(targetMinutes));
	}
}
