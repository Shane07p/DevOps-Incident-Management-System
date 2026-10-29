package com.group2.incident.common.ratelimit;

import lombok.Getter;

/**
 * Thrown when an incoming request exceeds the configured caller rate limit (HTTP 429).
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

	private final long retryAfterSeconds;

	public RateLimitExceededException(long retryAfterSeconds) {
		super("Rate limit exceeded");
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}
}
