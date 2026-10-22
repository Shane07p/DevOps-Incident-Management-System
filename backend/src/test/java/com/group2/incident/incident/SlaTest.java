package com.group2.incident.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.incident.service.SlaCalculator;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlaTest {

	private static final int TARGET_MINUTES = 60;
	private static final Instant START = Instant.parse("2026-10-19T10:00:00Z");

	@Test
	@DisplayName("TC-FR-16.3b: Elapsed time T - 1 minute is not breached")
	void elapsedBeforeTargetIsNotBreached() {
		Instant now = START.plus(Duration.ofMinutes(TARGET_MINUTES - 1));
		assertThat(SlaCalculator.isBreached(START, now, TARGET_MINUTES)).isFalse();
	}

	@Test
	@DisplayName("TC-FR-16.3c: Elapsed time exactly T is not breached")
	void elapsedExactlyAtTargetIsNotBreached() {
		Instant now = START.plus(Duration.ofMinutes(TARGET_MINUTES));
		assertThat(SlaCalculator.isBreached(START, now, TARGET_MINUTES)).isFalse();
	}

	@Test
	@DisplayName("TC-FR-16.3d: Elapsed time T + 1 second is breached")
	void elapsedOverTargetIsBreached() {
		Instant now = START.plus(Duration.ofMinutes(TARGET_MINUTES)).plusSeconds(1);
		assertThat(SlaCalculator.isBreached(START, now, TARGET_MINUTES)).isTrue();
	}

	@Test
	@DisplayName("TC-FR-16.4a: An incident open across a night and a weekend counts all continuous time")
	void incidentOpenAcrossWeekendCountsAllContinuousTime() {
		// Friday 17:00 UTC to Monday 09:00 UTC = 64 hours continuous = 3840 minutes
		Instant fridayEvening = Instant.parse("2026-10-16T17:00:00Z");
		Instant mondayMorning = Instant.parse("2026-10-19T09:00:00Z");

		// Target of 24 hours (1440 minutes)
		int targetMinutes = 1440;
		assertThat(SlaCalculator.isBreached(fridayEvening, mondayMorning, targetMinutes)).isTrue();
	}
}
