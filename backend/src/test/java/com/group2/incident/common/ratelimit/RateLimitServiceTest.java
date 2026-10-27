package com.group2.incident.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimitServiceTest {

	private static final Instant T0 = Instant.parse("2026-10-28T00:00:00Z");

	@Test
	void configurationOverridesChangeEffectiveThresholds() {
		RateLimitProperties customProperties = RateLimitProperties.of(
				new RateLimitProperties.LimitConfig(2, Duration.ofSeconds(10)),
				new RateLimitProperties.LimitConfig(3, Duration.ofSeconds(5))
		);

		Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
		RateLimitService service = new RateLimitService(customProperties, clock);

		// Login limit: capacity is 2
		assertThat(service.tryConsumeLogin("1.2.3.4", "alice").allowed()).isTrue();
		assertThat(service.tryConsumeLogin("1.2.3.4", "alice").allowed()).isTrue();
		RateLimitResult third = service.tryConsumeLogin("1.2.3.4", "alice");
		assertThat(third.allowed()).isFalse();
		assertThat(third.retryAfterSeconds()).isGreaterThanOrEqualTo(1);

		// Webhook limit: capacity is 3
		assertThat(service.tryConsumeWebhook(100L).allowed()).isTrue();
		assertThat(service.tryConsumeWebhook(100L).allowed()).isTrue();
		assertThat(service.tryConsumeWebhook(100L).allowed()).isTrue();
		RateLimitResult fourthWebhook = service.tryConsumeWebhook(100L);
		assertThat(fourthWebhook.allowed()).isFalse();
		assertThat(fourthWebhook.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void checkLoginThrowsRateLimitExceededException() {
		RateLimitProperties properties = RateLimitProperties.of(null, null);
		Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
		RateLimitService service = new RateLimitService(properties, clock);

		for (int i = 0; i < 5; i++) {
			service.checkLogin("1.1.1.1", "bob");
		}

		assertThatThrownBy(() -> service.checkLogin("1.1.1.1", "bob"))
				.isInstanceOf(RateLimitExceededException.class)
				.satisfies(ex -> {
					RateLimitExceededException rle = (RateLimitExceededException) ex;
					assertThat(rle.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
				});
	}

	@Test
	void clockAdvancementReplenishesTokens() {
		RateLimitProperties properties = RateLimitProperties.of(
				new RateLimitProperties.LimitConfig(2, Duration.ofSeconds(30)),
				new RateLimitProperties.LimitConfig(2, Duration.ofSeconds(5))
		);
		Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
		RateLimitService service = new RateLimitService(properties, clock);

		assertThat(service.tryConsumeLogin("1.1.1.1", "carol").allowed()).isTrue();
		assertThat(service.tryConsumeLogin("1.1.1.1", "carol").allowed()).isTrue();
		assertThat(service.tryConsumeLogin("1.1.1.1", "carol").allowed()).isFalse();

		// Advance clock by 31 seconds
		service.setClock(Clock.fixed(T0.plusSeconds(31), ZoneOffset.UTC));

		assertThat(service.tryConsumeLogin("1.1.1.1", "carol").allowed()).isTrue();
	}

	@Test
	void exhaustedCallersRemainThrottledWhenBucketTableIsFull() {
		// Limit to 3 buckets, capacity 2
		RateLimitProperties properties = RateLimitProperties.of(
				new RateLimitProperties.LimitConfig(2, Duration.ofSeconds(60)),
				new RateLimitProperties.LimitConfig(50, Duration.ofSeconds(1)),
				3
		);
		Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
		RateLimitService service = new RateLimitService(properties, clock);

		// Alice consumes all 2 tokens (exhausted)
		assertThat(service.tryConsumeLogin("1.1.1.1", "alice").allowed()).isTrue();
		assertThat(service.tryConsumeLogin("1.1.1.1", "alice").allowed()).isTrue();
		assertThat(service.tryConsumeLogin("1.1.1.1", "alice").allowed()).isFalse();

		// Bob and Carol each consume 1 token (active, not full)
		assertThat(service.tryConsumeLogin("1.1.1.2", "bob").allowed()).isTrue();
		assertThat(service.tryConsumeLogin("1.1.1.3", "carol").allowed()).isTrue();

		// Table is at max capacity (3) and all 3 buckets are active (< full capacity).
		// Dave arrives as a new caller: table cannot safely evict, rejects Dave without crashing or wiping.
		RateLimitResult daveResult = service.tryConsumeLogin("1.1.1.4", "dave");
		assertThat(daveResult.allowed()).isFalse();
		assertThat(daveResult.retryAfterSeconds()).isEqualTo(60);

		// Crucial verification: Alice's exhausted quota was NOT wiped or reset to full
		RateLimitResult aliceRetry = service.tryConsumeLogin("1.1.1.1", "alice");
		assertThat(aliceRetry.allowed())
				.as("Exhausted caller must remain rate-limited when table is full")
				.isFalse();
	}

	@Test
	void idleBucketsAreSafelyEvictedWhenTableIsFull() {
		RateLimitProperties properties = RateLimitProperties.of(
				new RateLimitProperties.LimitConfig(2, Duration.ofSeconds(10)),
				new RateLimitProperties.LimitConfig(50, Duration.ofSeconds(1)),
				2
		);
		Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
		RateLimitService service = new RateLimitService(properties, clock);

		// Alice consumes 1 token (1 remaining)
		assertThat(service.tryConsumeLogin("1.1.1.1", "alice").allowed()).isTrue();

		// Advance clock past refill duration: Alice's bucket refills to full capacity (2 tokens)
		service.setClock(Clock.fixed(T0.plusSeconds(15), ZoneOffset.UTC));

		// Bob consumes 1 token (1 remaining)
		assertThat(service.tryConsumeLogin("1.1.1.2", "bob").allowed()).isTrue();

		// Carol arrives. Table has 2 buckets. Alice's bucket is idle (2 >= 2 tokens), so it is evicted safely.
		RateLimitResult carolResult = service.tryConsumeLogin("1.1.1.3", "carol");
		assertThat(carolResult.allowed()).isTrue();
	}
}

