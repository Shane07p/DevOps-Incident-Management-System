package com.group2.incident.common.ratelimit;

/**
 * Result of a rate-limit consumption attempt.
 *
 * @param allowed whether the request was permitted
 * @param retryAfterSeconds integer seconds the caller should wait before retrying if rejected
 */
public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

	public static RateLimitResult permitted() {
		return new RateLimitResult(true, 0);
	}

	public static RateLimitResult rejected(long retryAfterSeconds) {
		return new RateLimitResult(false, Math.max(1, retryAfterSeconds));
	}
}
