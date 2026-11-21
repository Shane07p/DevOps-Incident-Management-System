package com.group2.incident.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.common.ratelimit.RateLimitProperties.LimitConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A missing or nonsensical setting falls back to a working limit, never to no limit or a broken one (D42). */
class RateLimitPropertiesTest {

	@Test
	void missingLimitsGetTheDocumentedDefaults() {
		RateLimitProperties properties = new RateLimitProperties(null, null, null, 0);

		assertThat(properties.login()).isEqualTo(new LimitConfig(5, Duration.ofSeconds(60)));
		assertThat(properties.webhook()).isEqualTo(new LimitConfig(50, Duration.ofSeconds(1)));
		assertThat(properties.pat()).isEqualTo(new LimitConfig(60, Duration.ofSeconds(60)));
		assertThat(properties.maxBuckets()).isEqualTo(10_000);
	}

	@Test
	void givenLimitsAreKept() {
		LimitConfig login = new LimitConfig(3, Duration.ofSeconds(30));
		RateLimitProperties properties = new RateLimitProperties(login, login, login, 50);

		assertThat(properties.login()).isSameAs(login);
		assertThat(properties.maxBuckets()).isEqualTo(50);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1})
	void aBucketTableThatIsNotPositiveGetsTheDefault(int maxBuckets) {
		assertThat(new RateLimitProperties(null, null, null, maxBuckets).maxBuckets()).isEqualTo(10_000);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -5})
	void aCapacityThatIsNotPositiveAllowsOneCall(long capacity) {
		assertThat(new LimitConfig(capacity, Duration.ofSeconds(60)).capacity()).isEqualTo(1);
	}

	@Test
	void aWindowThatIsMissingOrNotPositiveBecomesOneSecond() {
		assertThat(new LimitConfig(5, null).duration()).isEqualTo(Duration.ofSeconds(1));
		assertThat(new LimitConfig(5, Duration.ZERO).duration()).isEqualTo(Duration.ofSeconds(1));
		assertThat(new LimitConfig(5, Duration.ofSeconds(-10)).duration()).isEqualTo(Duration.ofSeconds(1));
		assertThat(new LimitConfig(5, Duration.ofSeconds(30)).duration()).isEqualTo(Duration.ofSeconds(30));
	}
}
