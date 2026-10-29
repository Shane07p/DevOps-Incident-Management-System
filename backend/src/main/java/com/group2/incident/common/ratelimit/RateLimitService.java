package com.group2.incident.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * In-memory rate limiting service using Bucket4j (D42, NFR-04.4).
 * Enforces per-caller rate limits on login attempts and alert webhooks.
 */
@Service
public class RateLimitService {

	private final RateLimitProperties properties;
	private final Clock clock;
	private final TimeMeter timeMeter;

	// Bounded, so made-up usernames can't grow memory without limit. A full cache drops a bucket instead
	// of turning callers away, and a bucket idle for its whole window would be full again anyway.
	private final Cache<String, Bucket> loginBuckets;
	private final Cache<Long, Bucket> webhookBuckets;

	public RateLimitService(RateLimitProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		this.timeMeter = new TimeMeter() {
			@Override
			public long currentTimeNanos() {
				return nowNanos();
			}

			@Override
			public boolean isWallClockBased() {
				return true;
			}
		};
		this.loginBuckets = cache(properties.login().duration());
		this.webhookBuckets = cache(properties.webhook().duration());
	}

	/**
	 * Tries to consume a token for a login attempt by (clientIp, username).
	 */
	public RateLimitResult tryConsumeLogin(String clientIp, String username) {
		String normalizedUsername = (username == null || username.isBlank())
				? "anonymous"
				: username.trim().toLowerCase(Locale.ROOT);
		String ip = (clientIp == null || clientIp.isBlank()) ? "127.0.0.1" : clientIp.trim();
		Bucket bucket = loginBuckets.get(ip + ":" + normalizedUsername, key -> createBucket(properties.login()));
		return consume(bucket);
	}

	/**
	 * Throws {@link RateLimitExceededException} if login rate limit is exceeded.
	 */
	public void checkLogin(String clientIp, String username) {
		throwIfRejected(tryConsumeLogin(clientIp, username));
	}

	/**
	 * Tries to consume a token for an incoming alert webhook call by authenticated tool ID.
	 */
	public RateLimitResult tryConsumeWebhook(Long toolId) {
		if (toolId == null) {
			return RateLimitResult.permitted();
		}
		return consume(webhookBuckets.get(toolId, id -> createBucket(properties.webhook())));
	}

	/**
	 * Throws {@link RateLimitExceededException} if the tool's webhook rate limit is exceeded.
	 */
	public void checkWebhook(Long toolId) {
		throwIfRejected(tryConsumeWebhook(toolId));
	}

	private static RateLimitResult consume(Bucket bucket) {
		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (probe.isConsumed()) {
			return RateLimitResult.permitted();
		}
		long retryAfterSeconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
		return RateLimitResult.rejected(retryAfterSeconds);
	}

	private static void throwIfRejected(RateLimitResult result) {
		if (!result.allowed()) {
			throw new RateLimitExceededException(result.retryAfterSeconds());
		}
	}

	private <K> Cache<K, Bucket> cache(Duration window) {
		return Caffeine.newBuilder()
				.maximumSize(properties.maxBuckets())
				.expireAfterAccess(window)
				.build();
	}

	private Bucket createBucket(RateLimitProperties.LimitConfig limit) {
		Bandwidth bandwidth = Bandwidth.builder()
				.capacity(limit.capacity())
				.refillIntervally(limit.capacity(), limit.duration())
				.build();

		return Bucket.builder()
				.withCustomTimePrecision(timeMeter)
				.addLimit(bandwidth)
				.build();
	}

	private long nowNanos() {
		Instant now = clock.instant();
		return now.getEpochSecond() * 1_000_000_000L + now.getNano();
	}
}
