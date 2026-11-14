package com.group2.incident.insights.service;

import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.followup.entity.FollowUp;
import com.group2.incident.followup.entity.FollowUpStatus;
import com.group2.incident.followup.repository.FollowUpRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.service.SlaCalculator;
import com.group2.incident.insights.dto.FollowUpMetrics;
import com.group2.incident.insights.dto.IncidentCounts;
import com.group2.incident.insights.dto.InsightsResponse;
import com.group2.incident.insights.dto.MttxMetrics;
import com.group2.incident.insights.dto.ServiceBreakdown;
import com.group2.incident.insights.dto.SeverityBreakdown;
import com.group2.incident.insights.dto.SlaBreachRate;
import com.group2.incident.notification.entity.Notification;
import com.group2.incident.notification.repository.NotificationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InsightsService {

	private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
	private static final LocalTime WORK_START = LocalTime.of(9, 0);
	private static final LocalTime WORK_END = LocalTime.of(18, 0);

	private static final Set<IncidentStatus> OPEN_STATUSES = Set.of(
			IncidentStatus.OPEN,
			IncidentStatus.ACKNOWLEDGED,
			IncidentStatus.INVESTIGATING,
			IncidentStatus.IDENTIFIED,
			IncidentStatus.MONITORING
	);

	private static final Set<IncidentStatus> RESOLVED_STATUSES = Set.of(
			IncidentStatus.RESOLVED,
			IncidentStatus.CLOSED
	);

	private final Clock clock;
	private final IncidentRepository incidentRepository;
	private final FollowUpRepository followUpRepository;
	private final NotificationRepository notificationRepository;

	@Transactional(readOnly = true)
	public InsightsResponse getInsights(int days) {
		Instant to = clock.instant();
		Instant from = to.minus(Duration.ofDays(days));

		List<Incident> incidents = incidentRepository.findAllInWindow(from, to);

		int total = incidents.size();
		int open = (int) incidents.stream().filter(i -> OPEN_STATUSES.contains(i.getStatus())).count();
		int resolved = (int) incidents.stream().filter(i -> RESOLVED_STATUSES.contains(i.getStatus())).count();
		IncidentCounts incidentCounts = new IncidentCounts(total, open, resolved);

		MttxMetrics mtta = computeMtta(incidents);
		MttxMetrics mttr = computeMttr(incidents);

		List<SeverityBreakdown> bySeverity = computeBySeverity(incidents);
		List<ServiceBreakdown> byService = computeByService(incidents);

		SlaBreachRate slaBreachRate = computeSlaBreachRate(incidents, to);

		FollowUpMetrics followUps = computeFollowUps(from, to);

		int outOfHoursPages = computeOutOfHoursPages(from, to);

		return new InsightsResponse(
				from,
				to,
				incidentCounts,
				mtta,
				mttr,
				bySeverity,
				byService,
				slaBreachRate,
				followUps,
				outOfHoursPages
		);
	}

	private MttxMetrics computeMtta(List<Incident> incidents) {
		List<Double> durations = incidents.stream()
				.filter(i -> i.getAcknowledgedAt() != null)
				.map(i -> Duration.between(i.getCreatedAt(), i.getAcknowledgedAt()).toMillis() / 60000.0)
				.sorted()
				.toList();
		return computeMttx(durations);
	}

	private MttxMetrics computeMttr(List<Incident> incidents) {
		List<Double> durations = incidents.stream()
				.filter(i -> i.getResolvedAt() != null)
				.map(i -> Duration.between(i.getCreatedAt(), i.getResolvedAt()).toMillis() / 60000.0)
				.sorted()
				.toList();
		return computeMttx(durations);
	}

	private MttxMetrics computeMttx(List<Double> sortedDurations) {
		if (sortedDurations.isEmpty()) {
			return new MttxMetrics(null, null, null);
		}
		int n = sortedDurations.size();
		double sum = sortedDurations.stream().mapToDouble(Double::doubleValue).sum();
		Double mean = roundToOneDecimal(sum / n);

		Double median;
		if (n % 2 == 1) {
			median = roundToOneDecimal(sortedDurations.get(n / 2));
		} else {
			median = roundToOneDecimal((sortedDurations.get(n / 2 - 1) + sortedDurations.get(n / 2)) / 2.0);
		}

		int p90Index = (int) Math.ceil(0.90 * n) - 1;
		Double p90 = roundToOneDecimal(sortedDurations.get(p90Index));

		return new MttxMetrics(mean, median, p90);
	}

	private List<SeverityBreakdown> computeBySeverity(List<Incident> incidents) {
		Map<Severity, Long> counts = incidents.stream()
				.collect(Collectors.groupingBy(Incident::getSeverity, Collectors.counting()));
		return Stream.of(Severity.values())
				.filter(counts::containsKey)
				.map(s -> new SeverityBreakdown(s, counts.get(s).intValue()))
				.toList();
	}

	private record ServiceKey(Long id, String name) {}

	private List<ServiceBreakdown> computeByService(List<Incident> incidents) {
		return incidents.stream()
				.filter(i -> i.getService() != null)
				.collect(Collectors.groupingBy(
						i -> new ServiceKey(i.getService().getId(), i.getService().getName()),
						Collectors.counting()))
				.entrySet().stream()
				.map(e -> new ServiceBreakdown(e.getKey().id(), e.getKey().name(), e.getValue().intValue()))
				.sorted(Comparator.comparingInt(ServiceBreakdown::count).reversed()
						.thenComparing(ServiceBreakdown::name))
				.toList();
	}

	private SlaBreachRate computeSlaBreachRate(List<Incident> incidents, Instant now) {
		int responseDenominator = 0;
		int responseBreaches = 0;
		for (Incident i : incidents) {
			Instant stopOrNow = i.getAcknowledgedAt() != null ? i.getAcknowledgedAt() : now;
			boolean isBreached = SlaCalculator.isBreached(i.getCreatedAt(), stopOrNow, i.getResponseTargetMinutes());
			boolean isMeasurable = i.getAcknowledgedAt() != null || isBreached;
			if (isMeasurable) {
				responseDenominator++;
				if (isBreached) {
					responseBreaches++;
				}
			}
		}
		Double responseRate = responseDenominator == 0 ? null : roundToThreeDecimals((double) responseBreaches / responseDenominator);

		int resolutionDenominator = 0;
		int resolutionBreaches = 0;
		for (Incident i : incidents) {
			Instant stopOrNow = i.getResolvedAt() != null ? i.getResolvedAt() : now;
			boolean isBreached = SlaCalculator.isBreached(i.getCreatedAt(), stopOrNow, i.getResolutionTargetMinutes());
			boolean isMeasurable = i.getResolvedAt() != null || isBreached;
			if (isMeasurable) {
				resolutionDenominator++;
				if (isBreached) {
					resolutionBreaches++;
				}
			}
		}
		Double resolutionRate = resolutionDenominator == 0 ? null : roundToThreeDecimals((double) resolutionBreaches / resolutionDenominator);

		return new SlaBreachRate(responseRate, resolutionRate);
	}

	private FollowUpMetrics computeFollowUps(Instant from, Instant to) {
		List<FollowUp> followUps = followUpRepository.findAllInWindow(from, to);
		LocalDate today = LocalDate.ofInstant(to, ZoneOffset.UTC);

		int total = followUps.size();
		int done = (int) followUps.stream().filter(f -> f.getStatus() == FollowUpStatus.DONE).count();
		int overdue = (int) followUps.stream().filter(f -> f.isOverdue(today)).count();
		Double completionRate = total == 0 ? null : roundToThreeDecimals((double) done / total);

		return new FollowUpMetrics(total, done, overdue, completionRate);
	}

	private int computeOutOfHoursPages(Instant from, Instant to) {
		List<Notification> notifications = notificationRepository.findAllIncidentNotificationsInWindow(from, to);
		return (int) notifications.stream()
				.filter(n -> isOutOfHours(n.getCreatedAt()))
				.count();
	}

	public static boolean isOutOfHours(Instant instant) {
		ZonedDateTime ist = instant.atZone(IST_ZONE);
		DayOfWeek dow = ist.getDayOfWeek();
		if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
			return true;
		}
		LocalTime time = ist.toLocalTime();
		return time.isBefore(WORK_START) || !time.isBefore(WORK_END);
	}

	private static Double roundToOneDecimal(double value) {
		return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
	}

	private static Double roundToThreeDecimals(double value) {
		return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
	}
}
