package com.group2.incident.monitor.service;

/** What one check found. {@code statusCode} and {@code responseTimeMs} are null when no answer came. */
public record ProbeOutcome(boolean success, Integer statusCode, Long responseTimeMs, String error) {

	public static ProbeOutcome passed(int statusCode, long responseTimeMs) {
		return new ProbeOutcome(true, statusCode, responseTimeMs, null);
	}

	public static ProbeOutcome failed(Integer statusCode, Long responseTimeMs, String error) {
		return new ProbeOutcome(false, statusCode, responseTimeMs, error);
	}
}
