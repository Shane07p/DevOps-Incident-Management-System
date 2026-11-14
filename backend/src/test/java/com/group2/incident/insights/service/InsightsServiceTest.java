package com.group2.incident.insights.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.followup.entity.FollowUp;
import com.group2.incident.followup.entity.FollowUpKind;
import com.group2.incident.followup.entity.FollowUpStatus;
import com.group2.incident.followup.repository.FollowUpRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.insights.dto.InsightsResponse;
import com.group2.incident.insights.dto.ServiceBreakdown;
import com.group2.incident.insights.dto.SeverityBreakdown;
import com.group2.incident.notification.entity.Notification;
import com.group2.incident.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InsightsServiceTest {

	// Fixed reference time: 2026-03-15T12:00:00Z (Sunday noon UTC, Sunday 17:30 IST)
	private static final Instant NOW = Instant.parse("2026-03-15T12:00:00Z");

	@Mock
	private IncidentRepository incidentRepository;

	@Mock
	private FollowUpRepository followUpRepository;

	@Mock
	private NotificationRepository notificationRepository;

	private Clock fixedClock;
	private InsightsService insightsService;

	private AppUser testUser;
	private ServiceEntity serviceA;
	private ServiceEntity serviceB;

	@BeforeEach
	void setUp() {
		fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
		insightsService = new InsightsService(fixedClock, incidentRepository, followUpRepository, notificationRepository);

		testUser = new AppUser("test@test.local", "Test User", "hashed", NOW);
		ReflectionTestUtils.setField(testUser, "id", 100L);

		serviceA = new ServiceEntity("Checkout", "Checkout service", testUser, Criticality.HIGH, NOW);
		ReflectionTestUtils.setField(serviceA, "id", 1L);

		serviceB = new ServiceEntity("Payments", "Payments service", testUser, Criticality.HIGH, NOW);
		ReflectionTestUtils.setField(serviceB, "id", 2L);
	}

	@Test
	@DisplayName("Empty data returns zero counts, empty lists, and null metrics")
	void emptyData_returnsZeroCountsAndNullMetrics() {
		Instant from = NOW.minus(Duration.ofDays(30));
		when(incidentRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(followUpRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(notificationRepository.findAllIncidentNotificationsInWindow(from, NOW)).thenReturn(List.of());

		InsightsResponse response = insightsService.getInsights(30);

		assertThat(response.from()).isEqualTo(from);
		assertThat(response.to()).isEqualTo(NOW);
		assertThat(response.incidents().total()).isZero();
		assertThat(response.incidents().open()).isZero();
		assertThat(response.incidents().resolved()).isZero();

		assertThat(response.mtta().mean()).isNull();
		assertThat(response.mtta().median()).isNull();
		assertThat(response.mtta().p90()).isNull();

		assertThat(response.mttr().mean()).isNull();
		assertThat(response.mttr().median()).isNull();
		assertThat(response.mttr().p90()).isNull();

		assertThat(response.bySeverity()).isEmpty();
		assertThat(response.byService()).isEmpty();

		assertThat(response.slaBreachRate().response()).isNull();
		assertThat(response.slaBreachRate().resolution()).isNull();

		assertThat(response.followUps().total()).isZero();
		assertThat(response.followUps().done()).isZero();
		assertThat(response.followUps().overdue()).isZero();
		assertThat(response.followUps().completionRate()).isNull();

		assertThat(response.outOfHoursPages()).isZero();
	}

	@Test
	@DisplayName("Correctly partitions open and resolved incident counts")
	void incidentCounts_correctlyClassifiesOpenAndResolved() {
		Instant from = NOW.minus(Duration.ofDays(7));
		Incident open1 = createIncident("Inc 1", serviceA, Severity.SEV1, IncidentStatus.OPEN, from.plusSeconds(100), 15, 60);
		Incident open2 = createIncident("Inc 2", serviceA, Severity.SEV2, IncidentStatus.ACKNOWLEDGED, from.plusSeconds(200), 15, 60);
		Incident open3 = createIncident("Inc 3", serviceA, Severity.SEV3, IncidentStatus.INVESTIGATING, from.plusSeconds(300), 15, 60);
		Incident open4 = createIncident("Inc 4", serviceA, Severity.SEV4, IncidentStatus.IDENTIFIED, from.plusSeconds(400), 15, 60);
		Incident open5 = createIncident("Inc 5", serviceA, Severity.SEV3, IncidentStatus.MONITORING, from.plusSeconds(500), 15, 60);
		Incident res1 = createIncident("Inc 6", serviceA, Severity.SEV2, IncidentStatus.RESOLVED, from.plusSeconds(600), 15, 60);
		Incident res2 = createIncident("Inc 7", serviceA, Severity.SEV1, IncidentStatus.CLOSED, from.plusSeconds(700), 15, 60);

		when(incidentRepository.findAllInWindow(from, NOW)).thenReturn(List.of(open1, open2, open3, open4, open5, res1, res2));
		when(followUpRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(notificationRepository.findAllIncidentNotificationsInWindow(from, NOW)).thenReturn(List.of());

		InsightsResponse response = insightsService.getInsights(7);

		assertThat(response.incidents().total()).isEqualTo(7);
		assertThat(response.incidents().open()).isEqualTo(5);
		assertThat(response.incidents().resolved()).isEqualTo(2);
	}

	@Test
	@DisplayName("Known MTTA and MTTR: mean, median, nearest-rank P90 calculated correctly")
	void knownMttaAndMttr_meanMedianP90CalculatedCorrectly() {
		Instant from = NOW.minus(Duration.ofDays(30));
		List<Incident> incidents = new ArrayList<>();

		// 10 incidents with known acknowledge delays: 10, 20, 30, 40, 50, 60, 70, 80, 90, 100 minutes
		// Mean: 55.0, Median: 55.0, P90 index: ceil(0.90 * 10) - 1 = 8 -> 90.0
		// Also resolution delays: 20, 40, 60, 80, 100, 120, 140, 160, 180, 200 minutes
		// Mean: 110.0, Median: 110.0, P90 index: ceil(0.90 * 10) - 1 = 8 -> 180.0
		for (int i = 1; i <= 10; i++) {
			Instant created = from.plus(Duration.ofHours(i));
			Incident inc = createIncident("Inc " + i, serviceA, Severity.SEV2, IncidentStatus.OPEN, created, 120, 300);
			inc.acknowledge(testUser, created.plus(Duration.ofMinutes(i * 10L)));
			inc.transitionTo(IncidentStatus.RESOLVED, created.plus(Duration.ofMinutes(i * 20L)));
			incidents.add(inc);
		}

		when(incidentRepository.findAllInWindow(from, NOW)).thenReturn(incidents);
		when(followUpRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(notificationRepository.findAllIncidentNotificationsInWindow(from, NOW)).thenReturn(List.of());

		InsightsResponse response = insightsService.getInsights(30);

		assertThat(response.mtta().mean()).isEqualTo(55.0);
		assertThat(response.mtta().median()).isEqualTo(55.0);
		assertThat(response.mtta().p90()).isEqualTo(90.0);

		assertThat(response.mttr().mean()).isEqualTo(110.0);
		assertThat(response.mttr().median()).isEqualTo(110.0);
		assertThat(response.mttr().p90()).isEqualTo(180.0);
	}

	@Test
	@DisplayName("Odd number of MTTA items selects exact middle for median")
	void oddNumberOfItems_medianSelectsMiddle() {
		Instant from = NOW.minus(Duration.ofDays(30));
		List<Incident> incidents = new ArrayList<>();
		// 3 incidents with ack delays: 10, 20, 30
		// Mean: 20.0, Median: 20.0, P90: ceil(0.9 * 3) - 1 = 3 - 1 = 2 -> 30.0
		for (int i = 1; i <= 3; i++) {
			Instant created = from.plus(Duration.ofHours(i));
			Incident inc = createIncident("Inc " + i, serviceA, Severity.SEV2, IncidentStatus.OPEN, created, 60, 240);
			inc.acknowledge(testUser, created.plus(Duration.ofMinutes(i * 10L)));
			incidents.add(inc);
		}

		when(incidentRepository.findAllInWindow(from, NOW)).thenReturn(incidents);
		when(followUpRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(notificationRepository.findAllIncidentNotificationsInWindow(from, NOW)).thenReturn(List.of());

		InsightsResponse response = insightsService.getInsights(30);

		assertThat(response.mtta().mean()).isEqualTo(20.0);
		assertThat(response.mtta().median()).isEqualTo(20.0);
		assertThat(response.mtta().p90()).isEqualTo(30.0);
	}

	@Test
	@DisplayName("Exact SLA target is not a breach; exceeding target by 1 second is a breach")
	void slaBreachRate_exactTargetIsNotBreach_exceededIsBreach() {
		Instant from = NOW.minus(Duration.ofDays(30));

		// Incident 1: Target = 15m, acknowledged at exactly 15m (900 seconds) -> NOT a breach
		Instant created1 = from.plus(Duration.ofHours(1));
		Incident inc1 = createIncident("Inc 1", serviceA, Severity.SEV1, IncidentStatus.OPEN, created1, 15, 60);
		inc1.acknowledge(testUser, created1.plusSeconds(900));

		// Incident 2: Target = 15m, acknowledged at 15m 1s (901 seconds) -> BREACH
		Instant created2 = from.plus(Duration.ofHours(2));
		Incident inc2 = createIncident("Inc 2", serviceA, Severity.SEV1, IncidentStatus.OPEN, created2, 15, 60);
		inc2.acknowledge(testUser, created2.plusSeconds(901));

		when(incidentRepository.findAllInWindow(from, NOW)).thenReturn(List.of(inc1, inc2));
		when(followUpRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(notificationRepository.findAllIncidentNotificationsInWindow(from, NOW)).thenReturn(List.of());

		InsightsResponse response = insightsService.getInsights(30);

		// Denominator = 2, breaches = 1 -> rate = 0.500
		assertThat(response.slaBreachRate().response()).isEqualTo(0.5);
	}

	@Test
	@DisplayName("In-flight unacknowledged and unresolved SLA events are excluded from Option B denominator")
	void slaBreachRate_inFlightEventsExcludedFromDenominator() {
		Instant from = NOW.minus(Duration.ofDays(7));

		// Incident created 5 minutes ago with target 15 minutes.
		// Not acknowledged yet, but elapsed time (5m) <= target (15m).
		// Response SLA is still in flight -> not measurable -> excluded from denominator!
		Instant created = NOW.minus(Duration.ofMinutes(5));
		Incident inFlight = createIncident("In flight", serviceA, Severity.SEV1, IncidentStatus.OPEN, created, 15, 60);

		when(incidentRepository.findAllInWindow(from, NOW)).thenReturn(List.of(inFlight));
		when(followUpRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(notificationRepository.findAllIncidentNotificationsInWindow(from, NOW)).thenReturn(List.of());

		InsightsResponse response = insightsService.getInsights(7);

		// Measurable events = 0 -> breach rates should be null!
		assertThat(response.slaBreachRate().response()).isNull();
		assertThat(response.slaBreachRate().resolution()).isNull();
	}

	@Test
	@DisplayName("Overdue unacknowledged/unresolved incidents are included in Option B denominator and counted as breach")
	void slaBreachRate_overdueInFlightIncidentsCountedAsBreached() {
		Instant from = NOW.minus(Duration.ofDays(7));

		// Incident created 20 minutes ago with target 15 minutes.
		// Still unacknowledged -> elapsed time (20m) > target (15m) -> overdue -> measurable AND breached!
		Instant created = NOW.minus(Duration.ofMinutes(20));
		Incident overdue = createIncident("Overdue", serviceA, Severity.SEV1, IncidentStatus.OPEN, created, 15, 60);

		when(incidentRepository.findAllInWindow(from, NOW)).thenReturn(List.of(overdue));
		when(followUpRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(notificationRepository.findAllIncidentNotificationsInWindow(from, NOW)).thenReturn(List.of());

		InsightsResponse response = insightsService.getInsights(7);

		// Response denominator = 1, breaches = 1 -> 1.000
		assertThat(response.slaBreachRate().response()).isEqualTo(1.0);
		// Resolution target is 60m; elapsed is 20m <= 60m -> resolution is still in flight -> null
		assertThat(response.slaBreachRate().resolution()).isNull();
	}

	@Test
	@DisplayName("Severity and service grouping: counts and ordering verified")
	void severityAndServiceGrouping() {
		Instant from = NOW.minus(Duration.ofDays(30));

		Incident i1 = createIncident("Inc 1", serviceA, Severity.SEV1, IncidentStatus.OPEN, from.plusSeconds(10), 15, 60);
		Incident i2 = createIncident("Inc 2", serviceA, Severity.SEV2, IncidentStatus.OPEN, from.plusSeconds(20), 15, 60);
		Incident i3 = createIncident("Inc 3", serviceB, Severity.SEV2, IncidentStatus.OPEN, from.plusSeconds(30), 15, 60);
		Incident i4 = createIncident("Inc 4", serviceB, Severity.SEV2, IncidentStatus.OPEN, from.plusSeconds(40), 15, 60);

		when(incidentRepository.findAllInWindow(from, NOW)).thenReturn(List.of(i1, i2, i3, i4));
		when(followUpRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(notificationRepository.findAllIncidentNotificationsInWindow(from, NOW)).thenReturn(List.of());

		InsightsResponse response = insightsService.getInsights(30);

		// bySeverity: ordered SEV1, SEV2
		assertThat(response.bySeverity()).containsExactly(
				new SeverityBreakdown(Severity.SEV1, 1),
				new SeverityBreakdown(Severity.SEV2, 3)
		);

		// byService: Payments (2), Checkout (2) -> sorted count desc (both 2), then name asc ("Checkout", "Payments")
		assertThat(response.byService()).containsExactly(
				new ServiceBreakdown(1L, "Checkout", 2),
				new ServiceBreakdown(2L, "Payments", 2)
		);
	}

	@Test
	@DisplayName("Follow-ups: calculates total, done, overdue, and completion rate")
	void followUps_calculatesDoneOverdueAndCompletionRate() {
		Instant from = NOW.minus(Duration.ofDays(30));
		LocalDate today = LocalDate.ofInstant(NOW, ZoneOffset.UTC); // 2026-03-15

		Incident incident = createIncident("Inc", serviceA, Severity.SEV1, IncidentStatus.OPEN, from.plusSeconds(10), 15, 60);

		// fu1: DONE, created in window
		FollowUp fu1 = new FollowUp(incident, "FU 1", FollowUpKind.CORRECTIVE, testUser, today.minusDays(5), from.plusSeconds(20));
		fu1.moveTo(FollowUpStatus.DONE, NOW);

		// fu2: OPEN, due 3 days ago -> OVERDUE
		FollowUp fu2 = new FollowUp(incident, "FU 2", FollowUpKind.PREVENTIVE, testUser, today.minusDays(3), from.plusSeconds(30));

		// fu3: OPEN, due tomorrow -> NOT overdue
		FollowUp fu3 = new FollowUp(incident, "FU 3", FollowUpKind.PREVENTIVE, testUser, today.plusDays(1), from.plusSeconds(40));

		when(incidentRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(followUpRepository.findAllInWindow(from, NOW)).thenReturn(List.of(fu1, fu2, fu3));
		when(notificationRepository.findAllIncidentNotificationsInWindow(from, NOW)).thenReturn(List.of());

		InsightsResponse response = insightsService.getInsights(30);

		// Total = 3, done = 1, overdue = 1, completionRate = 1 / 3 = 0.333
		assertThat(response.followUps().total()).isEqualTo(3);
		assertThat(response.followUps().done()).isEqualTo(1);
		assertThat(response.followUps().overdue()).isEqualTo(1);
		assertThat(response.followUps().completionRate()).isEqualTo(0.333);
	}

	@Test
	@DisplayName("Out-of-hours calculation: IST working hours and weekends")
	void outOfHoursPages_checksIstWorkingHoursAndWeekends() {
		// Asia/Kolkata is UTC+05:30.
		// Monday: 2026-03-16.
		// 09:00:00 IST = 03:30:00 UTC.
		// 18:00:00 IST = 12:30:00 UTC.

		// Mon 08:59:59 IST (03:29:59 UTC) -> Out of hours
		Instant monBeforeWork = Instant.parse("2026-03-16T03:29:59Z");
		assertThat(InsightsService.isOutOfHours(monBeforeWork)).isTrue();

		// Mon 09:00:00 IST (03:30:00 UTC) -> IN hours (09:00 inclusive)
		Instant monWorkStart = Instant.parse("2026-03-16T03:30:00Z");
		assertThat(InsightsService.isOutOfHours(monWorkStart)).isFalse();

		// Wed 14:00:00 IST (08:30:00 UTC) -> IN hours
		Instant wedMidday = Instant.parse("2026-03-18T08:30:00Z");
		assertThat(InsightsService.isOutOfHours(wedMidday)).isFalse();

		// Fri 17:59:59.999 IST (12:29:59.999 UTC) -> IN hours (18:00 exclusive)
		Instant friBeforeEnd = Instant.parse("2026-03-20T12:29:59.999Z");
		assertThat(InsightsService.isOutOfHours(friBeforeEnd)).isFalse();

		// Fri 18:00:00 IST (12:30:00 UTC) -> Out of hours
		Instant friWorkEnd = Instant.parse("2026-03-20T12:30:00Z");
		assertThat(InsightsService.isOutOfHours(friWorkEnd)).isTrue();

		// Fri 22:00:00 IST (16:30:00 UTC) -> Out of hours
		Instant friNight = Instant.parse("2026-03-20T16:30:00Z");
		assertThat(InsightsService.isOutOfHours(friNight)).isTrue();

		// Saturday 12:00:00 IST (06:30:00 UTC) -> Out of hours (weekend)
		Instant saturday = Instant.parse("2026-03-21T06:30:00Z");
		assertThat(InsightsService.isOutOfHours(saturday)).isTrue();

		// Sunday 12:00:00 IST (06:30:00 UTC) -> Out of hours (weekend)
		Instant sunday = Instant.parse("2026-03-22T06:30:00Z");
		assertThat(InsightsService.isOutOfHours(sunday)).isTrue();
	}

	@Test
	@DisplayName("Out-of-hours notifications: counts only out-of-hours incident notifications")
	void outOfHoursPages_countsOnlyOutOfHoursNotifications() {
		Instant from = NOW.minus(Duration.ofDays(7));
		Incident incident = createIncident("Inc", serviceA, Severity.SEV1, IncidentStatus.OPEN, from.plusSeconds(10), 15, 60);

		// One in-hours notification: Wednesday 14:00 IST (08:30 UTC)
		Notification inHours = new Notification(testUser, incident, "In hours page", Instant.parse("2026-03-11T08:30:00Z"));

		// Two out-of-hours notifications: Saturday and Friday night
		Notification outHours1 = new Notification(testUser, incident, "Saturday page", Instant.parse("2026-03-14T10:00:00Z"));
		Notification outHours2 = new Notification(testUser, incident, "Friday night page", Instant.parse("2026-03-13T16:30:00Z"));

		when(incidentRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(followUpRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(notificationRepository.findAllIncidentNotificationsInWindow(from, NOW))
				.thenReturn(List.of(inHours, outHours1, outHours2));

		InsightsResponse response = insightsService.getInsights(7);

		assertThat(response.outOfHoursPages()).isEqualTo(2);
	}

	@Test
	@DisplayName("Date boundary verification: half-open interval [from, to)")
	void dateBoundaries_halfOpenIntervalVerified() {
		Instant from = NOW.minus(Duration.ofDays(30));

		when(incidentRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(followUpRepository.findAllInWindow(from, NOW)).thenReturn(List.of());
		when(notificationRepository.findAllIncidentNotificationsInWindow(from, NOW)).thenReturn(List.of());

		insightsService.getInsights(30);

		verify(incidentRepository).findAllInWindow(eq(from), eq(NOW));
		verify(followUpRepository).findAllInWindow(eq(from), eq(NOW));
		verify(notificationRepository).findAllIncidentNotificationsInWindow(eq(from), eq(NOW));
	}

	private Incident createIncident(String title, ServiceEntity service, Severity severity,
			IncidentStatus status, Instant createdAt, int responseTarget, int resolutionTarget) {
		return new Incident(title, "Description", service, severity, status, testUser, createdAt, responseTarget, resolutionTarget);
	}
}
