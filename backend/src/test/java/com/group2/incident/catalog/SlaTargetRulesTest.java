package com.group2.incident.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.group2.incident.common.FieldValidationException;
import com.group2.incident.incident.Severity;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlaTargetRulesTest {

	@Test
	void keepsTargetsThatWereEntered() {
		Map<Severity, SlaTarget> targets = SlaTargetRules.resolve(all(10, 100));

		assertThat(targets).hasSize(4).containsEntry(Severity.SEV3, new SlaTarget(10, 100));
	}

	@Test
	void missingResponseTargetsTakeTheSurveyDefaults() {
		Map<Severity, SlaTarget> targets = SlaTargetRules.resolve(all(null, 2000));

		assertThat(targets.get(Severity.SEV1).responseMinutes()).isEqualTo(15);
		assertThat(targets.get(Severity.SEV2).responseMinutes()).isEqualTo(60);
		assertThat(targets.get(Severity.SEV3).responseMinutes()).isEqualTo(240);
		assertThat(targets.get(Severity.SEV4).responseMinutes()).isEqualTo(1440);
	}

	@Test
	void everySeverityNeedsTargets() {
		Map<Severity, SlaTargetDto> requested = all(10, 100);
		requested.remove(Severity.SEV4);

		assertField(requested, "targets.SEV4", "Targets for SEV4 are required");
	}

	@Test
	void noTargetsAtAllNamesTheFirstSeverity() {
		assertField(null, "targets.SEV1", "Targets for SEV1 are required");
	}

	@Test
	void resolutionTargetHasNoDefault() {
		assertField(all(10, null), "targets.SEV1.resolutionMinutes", "Resolution target is required");
	}

	@Test
	void resolutionMayEqualResponseButNotBeShorter() {
		assertThat(SlaTargetRules.resolve(all(30, 30)).get(Severity.SEV1)).isEqualTo(new SlaTarget(30, 30));

		assertField(all(30, 29), "targets.SEV1.resolutionMinutes",
				"Resolution target can't be shorter than the response target");
	}

	@Test
	void defaultResponseIsComparedWithResolution() {
		// SEV4 defaults to 1440 minutes, so a 600-minute resolution target is too short.
		Map<Severity, SlaTargetDto> requested = all(null, 2000);
		requested.put(Severity.SEV4, new SlaTargetDto(null, 600));

		assertField(requested, "targets.SEV4.resolutionMinutes",
				"Resolution target can't be shorter than the response target");
	}

	@Test
	void targetsMustBeBetweenOneMinuteAndOneYear() {
		assertThat(SlaTargetRules.resolve(all(1, SlaTargetRules.MAX_MINUTES)).get(Severity.SEV1))
				.isEqualTo(new SlaTarget(1, SlaTargetRules.MAX_MINUTES));

		assertField(all(0, 100), "targets.SEV1.responseMinutes", "Must be at least 1 minute");
		assertField(all(-5, 100), "targets.SEV1.responseMinutes", "Must be at least 1 minute");
		assertField(all(10, 0), "targets.SEV1.resolutionMinutes", "Must be at least 1 minute");
		assertField(all(10, SlaTargetRules.MAX_MINUTES + 1), "targets.SEV1.resolutionMinutes",
				"Must be at most 525600 minutes (one year)");
		assertField(all(SlaTargetRules.MAX_MINUTES + 1, SlaTargetRules.MAX_MINUTES + 1),
				"targets.SEV1.responseMinutes", "Must be at most 525600 minutes (one year)");
	}

	private static Map<Severity, SlaTargetDto> all(Integer response, Integer resolution) {
		Map<Severity, SlaTargetDto> targets = new EnumMap<>(Severity.class);
		for (Severity severity : Severity.values()) {
			targets.put(severity, new SlaTargetDto(response, resolution));
		}
		return targets;
	}

	private static void assertField(Map<Severity, SlaTargetDto> requested, String field, String message) {
		assertThatThrownBy(() -> SlaTargetRules.resolve(requested))
				.isInstanceOfSatisfying(FieldValidationException.class, ex -> {
					assertThat(ex.getField()).isEqualTo(field);
					assertThat(ex.getMessage()).isEqualTo(message);
				});
	}

}
