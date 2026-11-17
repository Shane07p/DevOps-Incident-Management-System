package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.incident.entity.IncidentStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit and Basis-Path test for {@link TransitionPolicy}.
 *
 * Requirements:
 * - TC-FR-06.3a: State transition - Each of the 8 legal pairs accepted (FR-06.3, DR-03)
 * - TC-FR-06.4a: State transition - Each of the 41 illegal pairs rejected (FR-06.4, DR-03)
 * - White-box basis-path testing with control-flow graph analysis and cyclomatic complexity V(G).
 *
 * CONTROL-FLOW GRAPH AND CYCLOMATIC COMPLEXITY ANALYSIS:
 * Method under test: {@link TransitionPolicy#isLegal(IncidentStatus, IncidentStatus)}
 *
 * Predicate Nodes (P):
 *   P1: from == null
 *   P2: to == null
 *   P3: case OPEN -> to == ACKNOWLEDGED
 *   P4: case ACKNOWLEDGED -> to == INVESTIGATING
 *   P5: case INVESTIGATING -> to == IDENTIFIED
 *   P6: case INVESTIGATING -> to == MONITORING (short-circuit or)
 *   P7: case IDENTIFIED -> to == MONITORING
 *   P8: case MONITORING -> to == INVESTIGATING
 *   P9: case MONITORING -> to == RESOLVED (short-circuit or)
 *   P10: case RESOLVED -> to == CLOSED
 *   (case CLOSED has no predicate, unconditionally false)
 *
 * Total predicates P = 10
 * Cyclomatic Complexity: V(G) = P + 1 = 11
 */
class TransitionPolicyTest {

	private final TransitionPolicy policy = new TransitionPolicy();

	private static final Set<String> LEGAL_TRANSITIONS = Set.of(
			"OPEN->ACKNOWLEDGED",
			"ACKNOWLEDGED->INVESTIGATING",
			"INVESTIGATING->IDENTIFIED",
			"INVESTIGATING->MONITORING",
			"IDENTIFIED->MONITORING",
			"MONITORING->INVESTIGATING",
			"MONITORING->RESOLVED",
			"RESOLVED->CLOSED"
	);

	// =========================================================================
	// TC-FR-06.3a: Parameterized test for each of the 8 legal transitions
	// =========================================================================

	static Stream<Arguments> legalTransitions() {
		return Stream.of(
				Arguments.of(IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED),
				Arguments.of(IncidentStatus.ACKNOWLEDGED, IncidentStatus.INVESTIGATING),
				Arguments.of(IncidentStatus.INVESTIGATING, IncidentStatus.IDENTIFIED),
				Arguments.of(IncidentStatus.INVESTIGATING, IncidentStatus.MONITORING),
				Arguments.of(IncidentStatus.IDENTIFIED, IncidentStatus.MONITORING),
				Arguments.of(IncidentStatus.MONITORING, IncidentStatus.INVESTIGATING),
				Arguments.of(IncidentStatus.MONITORING, IncidentStatus.RESOLVED),
				Arguments.of(IncidentStatus.RESOLVED, IncidentStatus.CLOSED)
		);
	}

	@ParameterizedTest(name = "Legal: {0} -> {1}")
	@MethodSource("legalTransitions")
	@DisplayName("TC-FR-06.3a: Each of the 8 legal pairs is accepted")
	void tcFr06_3a_legalTransitionsAccepted(IncidentStatus from, IncidentStatus to) {
		assertThat(policy.isLegal(from, to)).isTrue();
	}

	// =========================================================================
	// TC-FR-06.4a: Parameterized test for each of the 41 illegal transitions
	// =========================================================================

	static Stream<Arguments> illegalTransitions() {
		List<Arguments> illegal = new ArrayList<>();
		for (IncidentStatus from : IncidentStatus.values()) {
			for (IncidentStatus to : IncidentStatus.values()) {
				String key = from.name() + "->" + to.name();
				if (!LEGAL_TRANSITIONS.contains(key)) {
					illegal.add(Arguments.of(from, to));
				}
			}
		}
		return illegal.stream();
	}

	@ParameterizedTest(name = "Illegal: {0} -> {1}")
	@MethodSource("illegalTransitions")
	@DisplayName("TC-FR-06.4a: Each of the 41 illegal pairs is rejected")
	void tcFr06_4a_illegalTransitionsRejected(IncidentStatus from, IncidentStatus to) {
		assertThat(policy.isLegal(from, to)).isFalse();
	}

	@Test
	@DisplayName("Verify exact count: 8 legal and 41 illegal transitions (49 total)")
	void verifyTransitionMatrixCounts() {
		long totalPairs = IncidentStatus.values().length * IncidentStatus.values().length;
		assertThat(totalPairs).isEqualTo(49);
		assertThat(LEGAL_TRANSITIONS).hasSize(8);
		assertThat(totalPairs - LEGAL_TRANSITIONS.size()).isEqualTo(41);
	}

	// =========================================================================
	// White-Box Basis-Path Tests (V(G) = 11)
	// =========================================================================

	@Nested
	@DisplayName("White-Box / Basis-Path Tests")
	class BasisPathTests {

		@Test
		@DisplayName("Path 1: from == null returns false (P1 true branch)")
		void path1_nullFromReturnsFalse() {
			assertThat(policy.isLegal(null, IncidentStatus.OPEN)).isFalse();
		}

		@Test
		@DisplayName("Path 2: from != null, to == null returns false (P1 false, P2 true branch)")
		void path2_nullToReturnsFalse() {
			assertThat(policy.isLegal(IncidentStatus.OPEN, null)).isFalse();
		}

		@Test
		@DisplayName("Path 3: from == OPEN, to == ACKNOWLEDGED returns true (P3 true branch)")
		void path3_openToAcknowledgedReturnsTrue() {
			assertThat(policy.isLegal(IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED)).isTrue();
		}

		@Test
		@DisplayName("Path 4: from == OPEN, to != ACKNOWLEDGED returns false (P3 false branch)")
		void path4_openToOtherReturnsFalse() {
			assertThat(policy.isLegal(IncidentStatus.OPEN, IncidentStatus.RESOLVED)).isFalse();
		}

		@Test
		@DisplayName("Path 5: from == ACKNOWLEDGED, to == INVESTIGATING returns true (P4 true branch)")
		void path5_acknowledgedToInvestigatingReturnsTrue() {
			assertThat(policy.isLegal(IncidentStatus.ACKNOWLEDGED, IncidentStatus.INVESTIGATING)).isTrue();
		}

		@Test
		@DisplayName("Path 6: from == ACKNOWLEDGED, to != INVESTIGATING returns false (P4 false branch)")
		void path6_acknowledgedToOtherReturnsFalse() {
			assertThat(policy.isLegal(IncidentStatus.ACKNOWLEDGED, IncidentStatus.CLOSED)).isFalse();
		}

		@Test
		@DisplayName("Path 7: from == INVESTIGATING, to == IDENTIFIED returns true (P5 true branch)")
		void path7_investigatingToIdentifiedReturnsTrue() {
			assertThat(policy.isLegal(IncidentStatus.INVESTIGATING, IncidentStatus.IDENTIFIED)).isTrue();
		}

		@Test
		@DisplayName("Path 8: from == INVESTIGATING, to == MONITORING returns true (P5 false, P6 true branch)")
		void path8_investigatingToMonitoringReturnsTrue() {
			assertThat(policy.isLegal(IncidentStatus.INVESTIGATING, IncidentStatus.MONITORING)).isTrue();
		}

		@Test
		@DisplayName("Path 9: from == INVESTIGATING, to != IDENTIFIED && to != MONITORING returns false (P5 false, P6 false branch)")
		void path9_investigatingToOtherReturnsFalse() {
			assertThat(policy.isLegal(IncidentStatus.INVESTIGATING, IncidentStatus.RESOLVED)).isFalse();
		}

		@Test
		@DisplayName("Path 10: from == IDENTIFIED, to == MONITORING returns true (P7 true branch)")
		void path10_identifiedToMonitoringReturnsTrue() {
			assertThat(policy.isLegal(IncidentStatus.IDENTIFIED, IncidentStatus.MONITORING)).isTrue();
		}

		@Test
		@DisplayName("Path 11: from == IDENTIFIED, to != MONITORING returns false (P7 false branch)")
		void path11_identifiedToOtherReturnsFalse() {
			assertThat(policy.isLegal(IncidentStatus.IDENTIFIED, IncidentStatus.RESOLVED)).isFalse();
		}

		@Test
		@DisplayName("Path 12: from == MONITORING, to == INVESTIGATING returns true (P8 true branch)")
		void path12_monitoringToInvestigatingReturnsTrue() {
			assertThat(policy.isLegal(IncidentStatus.MONITORING, IncidentStatus.INVESTIGATING)).isTrue();
		}

		@Test
		@DisplayName("Path 13: from == MONITORING, to == RESOLVED returns true (P8 false, P9 true branch)")
		void path13_monitoringToResolvedReturnsTrue() {
			assertThat(policy.isLegal(IncidentStatus.MONITORING, IncidentStatus.RESOLVED)).isTrue();
		}

		@Test
		@DisplayName("Path 14: from == MONITORING, to != INVESTIGATING && to != RESOLVED returns false (P8 false, P9 false branch)")
		void path14_monitoringToOtherReturnsFalse() {
			assertThat(policy.isLegal(IncidentStatus.MONITORING, IncidentStatus.CLOSED)).isFalse();
		}

		@Test
		@DisplayName("Path 15: from == RESOLVED, to == CLOSED returns true (P10 true branch)")
		void path15_resolvedToClosedReturnsTrue() {
			assertThat(policy.isLegal(IncidentStatus.RESOLVED, IncidentStatus.CLOSED)).isTrue();
		}

		@Test
		@DisplayName("Path 16: from == RESOLVED, to != CLOSED returns false (P10 false branch)")
		void path16_resolvedToOtherReturnsFalse() {
			assertThat(policy.isLegal(IncidentStatus.RESOLVED, IncidentStatus.OPEN)).isFalse();
		}

		@Test
		@DisplayName("Path 17: from == CLOSED returns false for any destination status (terminal state)")
		void path17_closedToAnyReturnsFalse() {
			assertThat(policy.isLegal(IncidentStatus.CLOSED, IncidentStatus.OPEN)).isFalse();
			assertThat(policy.isLegal(IncidentStatus.CLOSED, IncidentStatus.RESOLVED)).isFalse();
		}
	}
}
