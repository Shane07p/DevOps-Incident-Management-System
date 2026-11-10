package com.group2.incident.statuspage.service;

import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.monitor.service.MonitorService;
import com.group2.incident.monitor.service.ServiceHealth;
import com.group2.incident.org.entity.Organization;
import com.group2.incident.org.repository.OrganizationRepository;
import com.group2.incident.statuspage.dto.PublicStatusResponse;
import com.group2.incident.statuspage.dto.PublicStatusResponse.PublishedIncident;
import com.group2.incident.statuspage.dto.PublicStatusResponse.PublishedService;
import com.group2.incident.statuspage.dto.PublicStatusResponse.UptimeDay;
import com.group2.incident.statuspage.dto.PublicUpdateResponse;
import com.group2.incident.statuspage.entity.PublicUpdate;
import com.group2.incident.statuspage.entity.PublicUpdateStatus;
import com.group2.incident.statuspage.repository.PublicUpdateRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds an org's public status page (#160, D45). The request has no login, so the slug picks the org and
 * everything is then read as that org (D48): the session's org is fixed when the transaction opens.
 */
@Service
public class PublicStatusService {

	static final int DAYS = 90;

	private static final Set<IncidentStatus> FINISHED = Set.of(IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

	private final OrganizationRepository organizations;

	private final ServiceRepository services;

	private final MonitorService monitorService;

	private final PublicUpdateRepository publicUpdates;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate readOnly;

	private final Clock clock;

	public PublicStatusService(OrganizationRepository organizations, ServiceRepository services,
			MonitorService monitorService, PublicUpdateRepository publicUpdates, JdbcTemplate jdbc,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.organizations = organizations;
		this.services = services;
		this.monitorService = monitorService;
		this.publicUpdates = publicUpdates;
		this.jdbc = jdbc;
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
		this.clock = clock;
	}

	public PublicStatusResponse statusPage(String slug) {
		Organization org = organizations.findBySlug(slug)
				.orElseThrow(() -> new NotFoundException("No status page at " + slug));
		return OrgContext.runAs(org.getId(), () -> readOnly.execute(status -> build(org)));
	}

	private PublicStatusResponse build(Organization org) {
		Instant now = clock.instant();
		LocalDate firstDay = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(DAYS - 1);
		Instant since = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();

		List<ServiceEntity> published = services.findAllByPublicVisibleTrueOrderByPublicNameAscIdAsc();
		if (published.isEmpty()) {
			return new PublicStatusResponse(org.getName(), List.of(), List.of(), List.of());
		}

		Map<Long, ServiceHealth> health = monitorService.healthByService();
		Map<Long, Map<LocalDate, long[]>> checks = checksByServiceAndDay(org.getId(), since);
		List<PublishedService> serviceViews = published.stream()
				.map(service -> toView(service, health.getOrDefault(service.getId(), ServiceHealth.UNKNOWN),
						checks.getOrDefault(service.getId(), Map.of()), firstDay))
				.toList();

		Map<Long, String> publicNames = published.stream()
				.collect(Collectors.toMap(ServiceEntity::getId, ServiceEntity::getPublicName));
		Map<Long, List<PublicUpdate>> updatesByIncident = new LinkedHashMap<>();
		for (PublicUpdate update : publicUpdates.findForStatusPage(publicNames.keySet(), FINISHED, since)) {
			updatesByIncident.computeIfAbsent(update.getIncident().getId(), id -> new ArrayList<>()).add(update);
		}

		List<PublishedIncident> active = new ArrayList<>();
		List<PublishedIncident> resolved = new ArrayList<>();
		for (List<PublicUpdate> updates : updatesByIncident.values()) {
			PublicUpdate latest = updates.getLast();
			Incident incident = latest.getIncident();
			String serviceName = publicNames.get(incident.getService().getId());
			List<PublicUpdateResponse> newestFirst = updates.reversed().stream().map(PublicUpdateResponse::from).toList();
			// Customers see it resolved once Comms says so, or once the team has resolved it.
			boolean publiclyResolved = latest.getStatus() == PublicUpdateStatus.RESOLVED;
			if (!publiclyResolved && !FINISHED.contains(incident.getStatus())) {
				active.add(new PublishedIncident(incident.getId(), serviceName, latest.getStatus(), newestFirst, null));
				continue;
			}
			Instant resolvedAt = publiclyResolved ? resolvedSince(updates) : latest.getCreatedAt();
			if (!resolvedAt.isBefore(since)) {
				resolved.add(new PublishedIncident(incident.getId(), serviceName, latest.getStatus(), newestFirst, resolvedAt));
			}
		}
		active.sort(Comparator.comparing((PublishedIncident incident) -> incident.updates().getFirst().createdAt()).reversed());
		resolved.sort(Comparator.comparing(PublishedIncident::resolvedAt).reversed());
		return new PublicStatusResponse(org.getName(), serviceViews, active, resolved);
	}

	/**
	 * Check counts per service and UTC day since {@code since}: {checks, passed}. Each check counts for the service
	 * its monitor watched when it ran, even if the monitor moved since. Raw SQL, so the org is named.
	 */
	private Map<Long, Map<LocalDate, long[]>> checksByServiceAndDay(long orgId, Instant since) {
		Map<Long, Map<LocalDate, long[]>> result = new HashMap<>();
		jdbc.query("""
				select r.service_id,
				       cast(r.checked_at at time zone 'UTC' as date) as day,
				       count(*) as checks,
				       count(*) filter (where r.success) as passed
				from monitor_check_result r
				where r.org_id = ? and r.checked_at >= ?
				group by r.service_id, day
				""", row -> {
			result.computeIfAbsent(row.getLong("service_id"), id -> new HashMap<>())
					.put(row.getObject("day", LocalDate.class), new long[] { row.getLong("checks"), row.getLong("passed") });
		}, orgId, Timestamp.from(since));
		return result;
	}

	private static PublishedService toView(ServiceEntity service, ServiceHealth health, Map<LocalDate, long[]> byDay,
			LocalDate firstDay) {
		long checks = 0;
		long passed = 0;
		List<UptimeDay> days = new ArrayList<>(DAYS);
		for (int i = 0; i < DAYS; i++) {
			LocalDate day = firstDay.plusDays(i);
			long[] counts = byDay.get(day);
			if (counts == null) {
				days.add(new UptimeDay(day, null));
				continue;
			}
			checks += counts[0];
			passed += counts[1];
			days.add(new UptimeDay(day, percent(counts[1], counts[0])));
		}
		return new PublishedService(service.getId(), service.getPublicName(), health,
				checks == 0 ? null : percent(passed, checks), days);
	}

	/**
	 * When the incident entered RESOLVED: the first of the RESOLVED updates it ends with, so a follow-up note
	 * neither moves the time nor brings an old incident back into the 90 days.
	 */
	static Instant resolvedSince(List<PublicUpdate> oldestFirst) {
		Instant since = oldestFirst.getLast().getCreatedAt();
		for (int i = oldestFirst.size() - 1; i >= 0 && oldestFirst.get(i).getStatus() == PublicUpdateStatus.RESOLVED; i--) {
			since = oldestFirst.get(i).getCreatedAt();
		}
		return since;
	}

	/** Two decimal places, so 2 of 3 is 66.67. */
	static double percent(long passed, long checks) {
		return Math.round(passed * 10_000.0 / checks) / 100.0;
	}
}
