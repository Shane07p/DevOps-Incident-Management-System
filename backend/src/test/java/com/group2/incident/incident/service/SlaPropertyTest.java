package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Property-based checks of the SLA rule (FR-16, AGENTS.md): jqwik tries 1,000 generated cases per property, and
 * shrinks a failure to the smallest one. SlaTest keeps the hand-picked boundaries (T - 1 min, T, T + 1 s).
 */
class SlaPropertyTest {

	// Exceeding the target is a breach; reaching it exactly is not. Elapsed time can be negative (a stop before the
	// start), which is never a breach.
	@Property
	void breachedExactlyWhenElapsedIsOverTheTarget(
			@ForAll @LongRange(min = 0, max = 4_000_000_000L) long startSecond,
			@ForAll @IntRange(min = 1, max = 100_000) int targetMinutes,
			@ForAll @LongRange(min = -1_000_000, max = 10_000_000) long elapsedSeconds) {
		Instant start = Instant.ofEpochSecond(startSecond);
		Instant stop = start.plusSeconds(elapsedSeconds);

		assertThat(SlaCalculator.isBreached(start, stop, targetMinutes)).isEqualTo(elapsedSeconds > targetMinutes * 60L);
	}

	// The due time is the start plus the target, and stopping at the due time itself is not yet a breach.
	@Property
	void theDueTimeIsTheLastMomentThatIsNotABreach(
			@ForAll @LongRange(min = 0, max = 4_000_000_000L) long startSecond,
			@ForAll @IntRange(min = 1, max = 100_000) int targetMinutes) {
		Instant start = Instant.ofEpochSecond(startSecond);
		Instant due = SlaCalculator.dueAt(start, targetMinutes);

		assertThat(Duration.between(start, due)).isEqualTo(Duration.ofMinutes(targetMinutes));
		assertThat(SlaCalculator.isBreached(start, due, targetMinutes)).isFalse();
		assertThat(SlaCalculator.isBreached(start, due.plusNanos(1), targetMinutes)).isTrue();
	}
}
