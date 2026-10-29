package com.group2.incident.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RateLimitServiceTest {

	private static final Instant T0 = Instant.parse("2026-10-28T00:00:00Z");
	private static final Clock FIXED = Clock.fixed(T0, ZoneOffset.UTC);

	@Test
	void configurationOverridesChangeEffectiveThresholds() {
		RateLimitService service = new RateLimitService(properties(2, Duration.ofSeconds(10), 3, Duration.ofSeconds(5), 10_000), FIXED);

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
		RateLimitService service = new RateLimitService(properties(5, Duration.ofSeconds(60), 50, Duration.ofSeconds(1), 10_000), FIXED);

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
		RateLimitService service = new RateLimitService(properties(2, Duration.ofSeconds(30), 2, Duration.ofSeconds(5), 10_000), FIXED);

		assertThat(service.tryConsumeLogin("1.1.1.1", "carol").allowed()).isTrue();
		assertThat(service.tryConsumeLogin("1.1.1.1", "carol").allowed()).isTrue();
		assertThat(service.tryConsumeLogin("1.1.1.1", "carol").allowed()).isFalse();

		// Advance clock by 31 seconds
		ReflectionTestUtils.setField(service, "clock", Clock.fixed(T0.plusSeconds(31), ZoneOffset.UTC));

		assertThat(service.tryConsumeLogin("1.1.1.1", "carol").allowed()).isTrue();
	}

	@Test
	void aFullBucketTableNeverTurnsANewCallerAway() {
		// Room for 2 buckets of 2 attempts each
		RateLimitService service = new RateLimitService(properties(2, Duration.ofSeconds(60), 50, Duration.ofSeconds(1), 2), FIXED);

		// Alice runs out; Bob is part-way through his
		assertThat(service.tryConsumeLogin("1.1.1.1", "alice").allowed()).isTrue();
		assertThat(service.tryConsumeLogin("1.1.1.1", "alice").allowed()).isTrue();
		assertThat(service.tryConsumeLogin("1.1.1.1", "alice").allowed()).isFalse();
		assertThat(service.tryConsumeLogin("1.1.1.2", "bob").allowed()).isTrue();

		// Made-up usernames fill the table; real callers still get in
		for (int i = 0; i < 100; i++) {
			assertThat(service.tryConsumeLogin("6.6.6.6", "made-up-" + i).allowed()).isTrue();
		}
		assertThat(service.tryConsumeLogin("1.1.1.4", "dave").allowed()).isTrue();
	}

	private static RateLimitProperties properties(long loginCapacity, Duration loginWindow, long webhookCapacity,
			Duration webhookWindow, int maxBuckets) {
		return new RateLimitProperties(
				new RateLimitProperties.LimitConfig(loginCapacity, loginWindow),
				new RateLimitProperties.LimitConfig(webhookCapacity, webhookWindow),
				maxBuckets);
	}
}
