package com.group2.incident.common.ratelimit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

/**
 * Configuration properties for caller rate limits (D42, NFR-04.4).
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
		@DefaultValue LimitConfig login,
		@DefaultValue LimitConfig webhook,
		@DefaultValue("10000") int maxBuckets,
		@DefaultValue List<String> trustedProxies
) {

	public static RateLimitProperties of(LimitConfig login, LimitConfig webhook) {
		return new RateLimitProperties(login, webhook, 10_000, List.of());
	}

	public static RateLimitProperties of(LimitConfig login, LimitConfig webhook, int maxBuckets) {
		return new RateLimitProperties(login, webhook, maxBuckets, List.of());
	}

	public RateLimitProperties {
		if (login == null) {
			login = new LimitConfig(5, Duration.ofSeconds(60));
		}
		if (webhook == null) {
			webhook = new LimitConfig(50, Duration.ofSeconds(1));
		}
		if (maxBuckets <= 0) {
			maxBuckets = 10_000;
		}
		if (trustedProxies == null) {
			trustedProxies = List.of();
		} else {
			trustedProxies = trustedProxies.stream()
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.toList();
		}
	}

	public record LimitConfig(
			@DefaultValue("5") long capacity,
			@DefaultValue("60s") @DurationUnit(ChronoUnit.SECONDS) Duration duration
	) {
		public LimitConfig {
			if (capacity <= 0) {
				capacity = 1;
			}
			if (duration == null || duration.isZero() || duration.isNegative()) {
				duration = Duration.ofSeconds(1);
			}
		}
	}
}

