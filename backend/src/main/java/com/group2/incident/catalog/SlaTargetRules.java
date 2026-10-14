package com.group2.incident.catalog;

import com.group2.incident.common.FieldValidationException;
import com.group2.incident.incident.Severity;
import java.util.EnumMap;
import java.util.Map;

/**
 * Checks requested SLA targets and fills in the default response targets. Plain Java so the rules
 * can be unit- and mutation-tested without Spring.
 */
final class SlaTargetRules {

	/** One year. Keeps deadline arithmetic far from integer overflow. */
	static final int MAX_MINUTES = 525_600;

	/** Survey defaults (C7, D23). Resolution targets have no default: the admin enters them. */
	static final Map<Severity, Integer> DEFAULT_RESPONSE_MINUTES = Map.of(
			Severity.SEV1, 15,
			Severity.SEV2, 60,
			Severity.SEV3, 240,
			Severity.SEV4, 1440);

	private SlaTargetRules() {
	}

	/**
	 * Every severity must have a target. A missing response target takes its default; a missing
	 * resolution target is an error. Resolution may not be shorter than response.
	 */
	static Map<Severity, SlaTarget> resolve(Map<Severity, SlaTargetDto> requested) {
		Map<Severity, SlaTarget> targets = new EnumMap<>(Severity.class);
		for (Severity severity : Severity.values()) {
			SlaTargetDto target = requested == null ? null : requested.get(severity);
			String field = "targets." + severity.name();
			if (target == null) {
				throw new FieldValidationException(field, "Targets for " + severity.name() + " are required");
			}
			int response = target.responseMinutes() == null
					? DEFAULT_RESPONSE_MINUTES.get(severity)
					: checkRange(field + ".responseMinutes", target.responseMinutes());
			if (target.resolutionMinutes() == null) {
				throw new FieldValidationException(field + ".resolutionMinutes", "Resolution target is required");
			}
			int resolution = checkRange(field + ".resolutionMinutes", target.resolutionMinutes());
			if (resolution < response) {
				throw new FieldValidationException(field + ".resolutionMinutes",
						"Resolution target can't be shorter than the response target");
			}
			targets.put(severity, new SlaTarget(response, resolution));
		}
		return targets;
	}

	private static int checkRange(String field, int minutes) {
		if (minutes < 1) {
			throw new FieldValidationException(field, "Must be at least 1 minute");
		}
		if (minutes > MAX_MINUTES) {
			throw new FieldValidationException(field, "Must be at most " + MAX_MINUTES + " minutes (one year)");
		}
		return minutes;
	}

}
