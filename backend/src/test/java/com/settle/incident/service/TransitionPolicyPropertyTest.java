package com.settle.incident.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.settle.incident.entity.IncidentStatus;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;

/**
 * Property-based checks of the incident lifecycle (DR-03, AGENTS.md). With two enums jqwik runs all 49 pairs
 * (exhaustive generation). The legal pairs below are copied from AGENTS.md, not from TransitionPolicy.
 */
class TransitionPolicyPropertyTest {

	private static final Set<String> LEGAL = Set.of(
			"OPEN>ACKNOWLEDGED", "ACKNOWLEDGED>INVESTIGATING", "INVESTIGATING>IDENTIFIED", "IDENTIFIED>MONITORING",
			"MONITORING>RESOLVED", "RESOLVED>CLOSED", "INVESTIGATING>MONITORING", "MONITORING>INVESTIGATING");

	private final TransitionPolicy policy = new TransitionPolicy();

	@Property(generation = GenerationMode.EXHAUSTIVE)
	void aPairIsLegalExactlyWhenTheLifecycleListsIt(@ForAll IncidentStatus from, @ForAll IncidentStatus to) {
		assertThat(policy.isLegal(from, to)).isEqualTo(LEGAL.contains(from + ">" + to));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	void nothingReturnsToOpenAndNothingLeavesClosed(@ForAll IncidentStatus status) {
		assertThat(policy.isLegal(status, IncidentStatus.OPEN)).isFalse();
		assertThat(policy.isLegal(IncidentStatus.CLOSED, status)).isFalse();
	}

	// Every status can still reach CLOSED, so no incident gets stuck, and no status skips straight to it but RESOLVED.
	@Property(generation = GenerationMode.EXHAUSTIVE)
	void everyStatusCanStillReachClosed(@ForAll IncidentStatus status) {
		Set<IncidentStatus> seen = EnumSet.of(status);
		ArrayDeque<IncidentStatus> queue = new ArrayDeque<>(seen);
		while (!queue.isEmpty()) {
			IncidentStatus current = queue.poll();
			for (IncidentStatus next : IncidentStatus.values()) {
				if (policy.isLegal(current, next) && seen.add(next)) {
					queue.add(next);
				}
			}
		}
		assertThat(seen).contains(IncidentStatus.CLOSED);
		assertThat(policy.isLegal(status, IncidentStatus.CLOSED)).isEqualTo(status == IncidentStatus.RESOLVED);
	}
}
