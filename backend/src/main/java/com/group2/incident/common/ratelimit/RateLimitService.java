package com.group2.incident.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * In-memory rate limiting service using Bucket4j (D42, NFR-04.4).
 * Enforces per-caller rate limits on login attempts and alert webhooks.
 */
@Slf4j
@Service
public class RateLimitService {

	private final RateLimitProperties properties;
	private final Clock defaultClock;
	private volatile Clock customClock;
	private final TimeMeter timeMeter;

	private final Map<String, Bucket> loginBuckets = new ConcurrentHashMap<>();
	private final Map<Long, Bucket> webhookBuckets = new ConcurrentHashMap<>();

	public RateLimitService(RateLimitProperties properties, Clock clock) {
		this.properties = properties;
		this.defaultClock = clock;
		this.timeMeter = new TimeMeter() {
			@Override
			public long currentTimeNanos() {
				Instant instant = getClock().instant();
				return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
			}

			@Override
			public boolean isWallClockBased() {
				return true;
			}
		};
	}

	public Clock getClock() {
		return customClock != null ? customClock : defaultClock;
	}

	/**
	 * Allows tests to supply a controlled clock for deterministic time manipulation.
	 */
	public void setClock(Clock clock) {
		this.customClock = clock;
	}

	public void resetClock() {
		this.customClock = null;
	}

	/**
	 * Clears all in-memory buckets.
	 */
	public void clear() {
		loginBuckets.clear();
		webhookBuckets.clear();
	}

	/**
	 * Tries to consume a token for a login attempt by (clientIp, username).
	 */
	public RateLimitResult tryConsumeLogin(String clientIp, String username) {
		String normalizedUsername = (username == null || username.isBlank())
				? "anonymous"
				: username.trim().toLowerCase(Locale.ROOT);
		String ip = (clientIp == null || clientIp.isBlank()) ? "127.0.0.1" : clientIp.trim();
		String key = ip + ":" + normalizedUsername;

		Bucket bucket = getOrCreateLoginBucket(key);
		if (bucket == null) {
			log.warn("Login rate-limit bucket table is saturated (max {}), rejecting request for key: {}",
					properties.maxBuckets(), key);
			return RateLimitResult.rejected(properties.login().duration().toSeconds());
		}

		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (probe.isConsumed()) {
			return RateLimitResult.permitted();
		}

		long waitNanos = probe.getNanosToWaitForRefill();
		long retryAfterSeconds = Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L);
		return RateLimitResult.rejected(retryAfterSeconds);
	}

	/**
	 * Throws {@link RateLimitExceededException} if login rate limit is exceeded.
	 */
	public void checkLogin(String clientIp, String username) {
		RateLimitResult result = tryConsumeLogin(clientIp, username);
		if (!result.allowed()) {
			throw new RateLimitExceededException(result.retryAfterSeconds());
		}
	}

	/**
	 * Tries to consume a token for an incoming alert webhook call by authenticated tool ID.
	 */
	public RateLimitResult tryConsumeWebhook(Long toolId) {
		if (toolId == null) {
			return RateLimitResult.permitted();
		}

		Bucket bucket = getOrCreateWebhookBucket(toolId);
		if (bucket == null) {
			log.warn("Webhook rate-limit bucket table is saturated (max {}), rejecting toolId: {}",
					properties.maxBuckets(), toolId);
			return RateLimitResult.rejected(properties.webhook().duration().toSeconds());
		}

		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (probe.isConsumed()) {
			return RateLimitResult.permitted();
		}

		long waitNanos = probe.getNanosToWaitForRefill();
		long retryAfterSeconds = Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L);
		return RateLimitResult.rejected(retryAfterSeconds);
	}

	private Bucket getOrCreateLoginBucket(String key) {
		Bucket bucket = loginBuckets.get(key);
		if (bucket != null) {
			return bucket;
		}
		int max = properties.maxBuckets();
		if (loginBuckets.size() >= max) {
			evictInactiveLoginBuckets();
			if (loginBuckets.size() >= max && !loginBuckets.containsKey(key)) {
				return null;
			}
		}
		return loginBuckets.computeIfAbsent(key, k -> createBucket(
				properties.login().capacity(),
				properties.login().duration()
		));
	}

	private Bucket getOrCreateWebhookBucket(Long toolId) {
		Bucket bucket = webhookBuckets.get(toolId);
		if (bucket != null) {
			return bucket;
		}
		int max = properties.maxBuckets();
		if (webhookBuckets.size() >= max) {
			evictInactiveWebhookBuckets();
			if (webhookBuckets.size() >= max && !webhookBuckets.containsKey(toolId)) {
				return null;
			}
		}
		return webhookBuckets.computeIfAbsent(toolId, id -> createBucket(
				properties.webhook().capacity(),
				properties.webhook().duration()
		));
	}

	private void evictInactiveLoginBuckets() {
		long capacity = properties.login().capacity();
		loginBuckets.entrySet().removeIf(entry -> entry.getValue().getAvailableTokens() >= capacity);
	}

	private void evictInactiveWebhookBuckets() {
		long capacity = properties.webhook().capacity();
		webhookBuckets.entrySet().removeIf(entry -> entry.getValue().getAvailableTokens() >= capacity);
	}

	private Bucket createBucket(long capacity, Duration duration) {
		Bandwidth limit = Bandwidth.builder()
				.capacity(capacity)
				.refillIntervally(capacity, duration)
				.build();

		return Bucket.builder()
				.withCustomTimePrecision(timeMeter)
				.addLimit(limit)
				.build();
	}
}
