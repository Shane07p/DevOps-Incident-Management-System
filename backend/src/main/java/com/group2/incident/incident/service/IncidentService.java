package com.group2.incident.incident.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.entity.SlaTarget;
import com.group2.incident.catalog.service.OnCallService;
import com.group2.incident.catalog.service.ServiceCatalogService;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.deploy.dto.DeployResponse;
import com.group2.incident.deploy.entity.Deployment;
import com.group2.incident.deploy.repository.DeploymentRepository;
import com.group2.incident.incident.dto.AcknowledgeRequest;
import com.group2.incident.incident.dto.AssignRoleRequest;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.dto.IncidentRolesDto;
import com.group2.incident.incident.dto.IncidentSearchPageResponse;
import com.group2.incident.incident.dto.IncidentSearchResult;
import com.group2.incident.incident.dto.IncidentServiceDto;
import com.group2.incident.incident.dto.IncidentSummaryResponse;
import com.group2.incident.incident.dto.IncidentUserDto;
import com.group2.incident.incident.dto.ReassignRequest;
import com.group2.incident.incident.dto.TimelineEntryResponse;
import com.group2.incident.incident.dto.SeverityChangeRequest;
import com.group2.incident.incident.dto.TransitionRequest;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentRole;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.IncidentSearchRow;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.notification.service.NotificationService;
import com.group2.incident.deploy.entity.IncidentDeployment;
import com.group2.incident.deploy.repository.IncidentDeploymentRepository;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.OrganizationRepository;
import com.group2.incident.org.service.OrgMembers;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncidentService {

	private static final int SEARCH_PAGE_SIZE = 20;

	private static final String STALE_CHANGE = "This record was changed by another request; reload and try again";

	private final IncidentRepository incidentRepository;
	private final TimelineEntryRepository timelineEntryRepository;
	private final ServiceCatalogService serviceCatalogService;
	private final OnCallService onCallService;
	private final AppUserRepository appUserRepository;
	private final AuditService auditService;
	private final EscalationService escalationService;
	private final NotificationService notificationService;
	private final OrganizationRepository orgs;
	private final OrgMembers orgMembers;
	private final DeploymentRepository deploymentRepository;
	private final IncidentDeploymentRepository incidentDeploymentRepository;
	private final TransitionPolicy transitionPolicy;
	private final Clock clock;

	@Transactional
	public IncidentResponse createIncident(CreateIncidentRequest request, Long reporterUserId) {
		return createIncident(request, reporterUserId, null, null);
	}

	@Transactional
	public IncidentResponse createIncidentFromAlert(
			CreateIncidentRequest request,
			String correlationSource,
			String correlationKey
	) {
		return createIncident(request, null, correlationSource, correlationKey);
	}

	private IncidentResponse createIncident(
			CreateIncidentRequest request,
			Long reporterUserId,
			String correlationSource,
			String correlationKey
	) {
		AppUser reporter = null;
		if (reporterUserId != null) {
			reporter = appUserRepository.findById(reporterUserId)
					.orElseThrow(() -> new NotFoundException("User " + reporterUserId + " not found"));
		}

		ServiceEntity service = serviceCatalogService.requireForIncident(request.serviceId());

		// Copied now and never changed afterwards, even if the service's targets change.
		SlaTarget target = service.getTargets().get(request.severity());
		if (target == null) {
			throw new FieldValidationException("severity",
					"Service " + service.getName() + " has no SLA target for " + request.severity());
		}

		Instant now = clock.instant();

		Incident incident = new Incident(
				request.title(),
				request.description(),
				service,
				request.severity(),
				IncidentStatus.OPEN,
				reporter,
				now,
				target.getResponseMinutes(),
				target.getResolutionMinutes(),
				correlationSource,
				correlationKey
		);
		// Each org numbers its incidents from 1; the row lock serialises concurrent creations (D48).
		incident.assignNumber(orgs.findLockedById(OrgContext.current())
				.orElseThrow(() -> new IllegalStateException("No org for this request"))
				.takeIncidentNumber());
		// Whoever is on call now, or the service's fallback contact outside every shift (FR-15).
		AppUser assignee = onCallService.resolveAssignee(service, now);
		if (assignee != null) {
			incident.assignTo(assignee);
		}
		incident = incidentRepository.save(incident);

		String details = "Reported as " + request.severity() + " on " + service.getName();
		TimelineEntry timelineEntry = new TimelineEntry(
				incident,
				reporter,
				TimelineEventType.INCIDENT_CREATED,
				details,
				now
		);
		List<TimelineEntry> timeline = new ArrayList<>();
		timeline.add(timelineEntryRepository.save(timelineEntry));

		if (assignee != null) {
			// A system action, so there is no actor (FR-05.1).
			timeline.add(timelineEntryRepository.save(new TimelineEntry(
					incident,
					null,
					TimelineEventType.ASSIGNED,
					"Assigned to " + assignee.getDisplayName(),
					now
			)));
			// Same transaction as the incident, so it rolls back with it (FR-07.1, FR-07.8, D33).
			notificationService.createNotification(
					assignee,
					incident,
					"You have been assigned to incident " + incident.getReference() + ": " + incident.getTitle()
			);
		}

		auditService.record(
				reporter,
				AuditAction.INCIDENT_CREATED,
				AuditItemType.INCIDENT,
				incident.getId(),
				"Created incident " + incident.getReference() + " on service " + service.getName()
		);

		escalationService.createTaskForIncident(incident, now);

		return toResponse(incident, timeline);
	}

	@Transactional(readOnly = true)
	public IncidentResponse getIncident(Long id) {
		Incident incident = incidentRepository.findWithDetailsById(id)
				.orElseThrow(() -> new NotFoundException("Incident " + id + " not found"));
		List<TimelineEntry> timeline = timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(id);

		// Explicitly linked deployments are always visible regardless of the 2-hour window (Issue #152)
		List<Deployment> linkedDeploys = incidentDeploymentRepository.findDeploymentsByIncidentId(id);

		// Candidate deployments within the 2-hour window [createdAt - 2h, createdAt] for the incident's service
		Instant windowStart = incident.getCreatedAt().minus(Duration.ofHours(2));
		Instant windowEnd = incident.getCreatedAt();
		List<Deployment> candidateDeploys = deploymentRepository
				.findInWindow(incident.getService().getId(), windowStart, windowEnd);

		Map<Long, Deployment> combined = new LinkedHashMap<>();
		for (Deployment d : linkedDeploys) {
			combined.put(d.getId(), d);
		}
		for (Deployment d : candidateDeploys) {
			combined.putIfAbsent(d.getId(), d);
		}

		List<DeployResponse> deploys = combined.values().stream()
				.sorted(Comparator.comparing(Deployment::getDeployedAt, Comparator.reverseOrder())
						.thenComparing(Deployment::getId, Comparator.reverseOrder()))
				.map(DeployResponse::from)
				.toList();

		return toResponse(incident, timeline, deploys);
	}

	@Transactional(readOnly = true)
	public List<IncidentSummaryResponse> listIncidents() {
		return incidentRepository.findAllByOrderByCreatedAtDescIdDesc()
				.stream()
				.map(i -> new IncidentSummaryResponse(
						i.getId(),
						i.getReference(),
						i.getTitle(),
						i.getService().getName(),
						i.getSeverity(),
						i.getStatus(),
						i.getCreatedAt(),
						i.getResponseDueAt(),
						i.getResolutionDueAt()
				))
				.toList();
	}

	/**
	 * Searches the current organization's incident text and applies the optional filters.
	 *
	 * @param query keyword or PostgreSQL web-search query
	 * @param serviceId optional service filter
	 * @param severity optional severity filter
	 * @param status optional incident-status filter
	 * @param page zero-based page number
	 * @return the matching incidents and pagination metadata
	 */
	@Transactional(readOnly = true)
	public IncidentSearchPageResponse searchIncidents(
			String query,
			Long serviceId,
			Severity severity,
			IncidentStatus status,
			int page
	) {
		if (page < 0) {
			throw new FieldValidationException("page", "must not be negative");
		}

		String severityName = severity != null ? severity.name() : null;
		String statusName = status != null ? status.name() : null;
		PageRequest pageRequest = PageRequest.of(page, SEARCH_PAGE_SIZE);
		Page<IncidentSearchRow> result = query == null || query.isBlank()
				? incidentRepository.searchWithoutKeyword(
						OrgContext.current(), serviceId, severityName, statusName, pageRequest)
				: incidentRepository.search(
						query.trim(), OrgContext.current(), serviceId, severityName, statusName, pageRequest);

		return new IncidentSearchPageResponse(
				result.getContent().stream().map(IncidentService::toSearchResult).toList(),
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages());
	}

	/** Maps a database search row to a response and identifies every matching field. */
	private static IncidentSearchResult toSearchResult(IncidentSearchRow row) {
		List<String> matchedIn = new ArrayList<>(3);
		if (Boolean.TRUE.equals(row.getTitleMatch())) {
			matchedIn.add("title");
		}
		if (Boolean.TRUE.equals(row.getDescriptionMatch())) {
			matchedIn.add("description");
		}
		if (Boolean.TRUE.equals(row.getTimelineMatch())) {
			matchedIn.add("timeline");
		}
		return new IncidentSearchResult(
				row.getIncidentId(),
				Incident.reference(row.getIncidentNumber()),
				row.getServiceId(),
				row.getTitle(),
				row.getServiceName(),
				Severity.valueOf(row.getSeverity()),
				IncidentStatus.valueOf(row.getStatus()),
				row.getCreatedAt(),
				List.copyOf(matchedIn));
	}

	/** Maps an incident and its append-only timeline, including correction links, to API data. */
	private static IncidentResponse toResponse(Incident incident, List<TimelineEntry> timeline) {
		return toResponse(incident, timeline, List.of());
	}

	private static IncidentResponse toResponse(
			Incident incident,
			List<TimelineEntry> timeline,
			List<DeployResponse> deploys
	) {
		IncidentServiceDto serviceDto = new IncidentServiceDto(
				incident.getService().getId(),
				incident.getService().getName()
		);
		IncidentUserDto reporterDto = toUserDto(incident.getReporter());
		IncidentUserDto assigneeDto = toUserDto(incident.getAssignee());
		IncidentRolesDto rolesDto = new IncidentRolesDto(toUserDto(incident.getComms()), toUserDto(incident.getOps()));

		List<TimelineEntryResponse> timelineResponses = timeline.stream()
				.map(t -> new TimelineEntryResponse(
						t.getId(),
						t.getEventType(),
						t.getActor() != null ? t.getActor().getDisplayName() : null,
						t.getDetails(),
						t.getRecordedAt(),
						t.getCorrectsEntry() != null ? t.getCorrectsEntry().getId() : null
				))
				.toList();

		return new IncidentResponse(
				incident.getId(),
				incident.getReference(),
				incident.getTitle(),
				incident.getDescription(),
				serviceDto,
				incident.getSeverity(),
				incident.getStatus(),
				incident.getVersion(),
				reporterDto,
				assigneeDto,
				rolesDto,
				roleWarning(incident),
				incident.getCreatedAt(),
				incident.getResponseTargetMinutes(),
				incident.getResolutionTargetMinutes(),
				incident.getResponseDueAt(),
				incident.getResolutionDueAt(),
				incident.getAcknowledgedAt(),
				incident.getResolvedAt(),
				timelineResponses,
				deploys != null ? deploys : List.of()
		);
	}

	/**
	 * Warns, without refusing, while one person holds more than one incident role on a SEV1 or SEV2
	 * incident (FR-05.5). Worked out from the incident as it stands, so a reassignment that makes the
	 * Lead also Comms or Ops shows it too. Null when there is nothing to warn about.
	 */
	private static String roleWarning(Incident incident) {
		if (incident.getSeverity() != Severity.SEV1 && incident.getSeverity() != Severity.SEV2) {
			return null;
		}
		for (IncidentRole role : IncidentRole.values()) {
			AppUser person = incident.getRoleUser(role);
			if (person == null) {
				continue;
			}
			List<String> held = Arrays.stream(IncidentRole.values())
					.filter(r -> incident.getRoleUser(r) != null && incident.getRoleUser(r).getId().equals(person.getId()))
					.map(IncidentService::roleName)
					.toList();
			if (held.size() > 1) {
				String roles = held.size() == 2 ? held.get(0) + " and " + held.get(1) : "Lead, Comms and Ops";
				return "%s holds %s on this %s incident".formatted(person.getDisplayName(), roles, incident.getSeverity());
			}
		}
		return null;
	}

	private static String roleName(IncidentRole role) {
		return switch (role) {
			case LEAD -> "Lead";
			case COMMS -> "Comms";
			case OPS -> "Ops";
		};
	}

	private static IncidentUserDto toUserDto(AppUser user) {
		return user != null ? new IncidentUserDto(user.getId(), user.getDisplayName()) : null;
	}

	/** Persists a comment as an independent timeline insert, including for closed incidents. */
	@Transactional
	public TimelineEntryResponse addComment(Long incidentId, String comment, Long actorId) {
		Incident incident = requireIncident(incidentId);
		AppUser actor = requireActor(actorId);
		TimelineEntry entry = timelineEntryRepository.save(new TimelineEntry(
				incident,
				actor,
				TimelineEventType.COMMENT,
				comment,
				clock.instant()
		));
		return toTimelineEntryResponse(entry);
	}

	/** Adds an evidence link without changing incident version or status. */
	@Transactional
	public TimelineEntryResponse addEvidence(Long incidentId, String url, String label, Long actorId) {
		// EvidenceRequest already refuses a blank URL or label.
		String normalizedUrl = url.trim();
		URI uri;
		try {
			uri = URI.create(normalizedUrl);
		} catch (IllegalArgumentException ex) {
			throw new FieldValidationException("url", "Invalid URL");
		}
		String scheme = uri.getScheme();
		if (scheme == null
				|| !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
				|| uri.getHost() == null || uri.getHost().isBlank()
				|| uri.getUserInfo() != null) {
			throw new FieldValidationException("url", "Only valid http and https links are allowed");
		}
		Incident incident = requireIncident(incidentId);
		AppUser actor = requireActor(actorId);
		String details = label.trim() + ": " + normalizedUrl;
		TimelineEntry entry = timelineEntryRepository.save(new TimelineEntry(
				incident, actor, TimelineEventType.EVIDENCE_ADDED, details, clock.instant()));
		return toTimelineEntryResponse(entry);
	}

	/** Appends an attributed correction while preserving the original timeline entry. */
	@Transactional
	public TimelineEntryResponse correctTimelineEntry(
			Long incidentId, Long entryId, String details, Long actorId) {
		Incident incident = requireIncident(incidentId);
		TimelineEntry original = timelineEntryRepository.findByIdAndIncidentId(entryId, incidentId)
				.orElseThrow(() -> new NotFoundException(
						"Timeline entry " + entryId + " not found for incident " + incidentId));
		AppUser actor = requireActor(actorId);
		TimelineEntry correction = timelineEntryRepository.save(new TimelineEntry(
				incident,
				actor,
				TimelineEventType.CORRECTION,
				details,
				clock.instant(),
				original
		));
		return toTimelineEntryResponse(correction);
	}

	/** Links a deployment to an incident, persisting the relationship and writing a timeline entry (#152). */
	@Transactional
	public TimelineEntryResponse linkDeployment(Long incidentId, Long deployId, Long actorId) {
		Incident incident = requireIncidentForUpdate(incidentId);
		AppUser actor = requireActor(actorId);
		Deployment deployment = deploymentRepository.findById(deployId)
				.orElseThrow(() -> new NotFoundException("Deployment " + deployId + " not found"));

		String details = "Linked deployment " + deployment.getVersion() + " (" + deployment.getCommit() + ") for "
				+ deployment.getService().getName();

		// Duplicate detection: query the actual persisted relationship instead of comparing timeline text
		if (incidentDeploymentRepository.existsByIncidentIdAndDeploymentId(incidentId, deployId)) {
			List<TimelineEntry> existing = timelineEntryRepository
					.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId);
			for (TimelineEntry entry : existing) {
				if (entry.getEventType() == TimelineEventType.DEPLOY_LINKED && details.equals(entry.getDetails())) {
					return toTimelineEntryResponse(entry);
				}
			}
		}

		try {
			incidentDeploymentRepository.saveAndFlush(new IncidentDeployment(incident, deployment, clock.instant()));
		}
		catch (DataIntegrityViolationException ex) {
			// Catch concurrent duplicate insertion caught by (incident_id, deployment_id) unique constraint
		}

		List<TimelineEntry> existing = timelineEntryRepository
				.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId);
		for (TimelineEntry entry : existing) {
			if (entry.getEventType() == TimelineEventType.DEPLOY_LINKED && details.equals(entry.getDetails())) {
				return toTimelineEntryResponse(entry);
			}
		}

		TimelineEntry timelineEntry = timelineEntryRepository.save(new TimelineEntry(
				incident,
				actor,
				TimelineEventType.DEPLOY_LINKED,
				details,
				clock.instant()
		));
		return toTimelineEntryResponse(timelineEntry);
	}

	/**
	 * Acknowledges an OPEN incident (US-08, FR-06, FR-07.4, FR-16.2).
	 * Sets status to ACKNOWLEDGED, makes the acknowledger the assignee, records the timeline entry,
	 * stops the response clock, and cancels pending escalation.
	 */
	@Transactional
	public IncidentResponse acknowledgeIncident(Long incidentId, AcknowledgeRequest request, Long actorId) {
		if (request == null || request.expectedVersion() == null) {
			throw new FieldValidationException("expectedVersion", "The incident version you saw is required");
		}

		Incident incident = requireIncident(incidentId);
		requireVersion(incident, request.expectedVersion());

		if (incident.getStatus() != IncidentStatus.OPEN) {
			throw new ConflictException("Cannot acknowledge an incident in " + incident.getStatus() + " status");
		}

		AppUser actor = requireActor(actorId);
		Instant now = clock.instant();

		incident.acknowledge(actor, now);

		try {
			incident = incidentRepository.saveAndFlush(incident);
		}
		catch (OptimisticLockingFailureException ex) {
			throw new ConflictException(STALE_CHANGE);
		}

		escalationService.cancelTaskForIncident(incidentId, now);

		String details = "Acknowledged by " + actor.getDisplayName();
		TimelineEntry timelineEntry = new TimelineEntry(
				incident,
				actor,
				TimelineEventType.ACKNOWLEDGED,
				details,
				now
		);
		timelineEntryRepository.save(timelineEntry);

		List<TimelineEntry> timeline = timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId);
		return toResponse(incident, timeline);
	}

	/**
	 * Transitions an incident to a new legal status (FR-06, US-10).
	 * Enforces legal transitions, optimistic locking, non-blank recovery notes for RESOLVED,
	 * appends a timeline entry with authenticated actor and timestamp, and returns the updated incident.
	 */
	@Transactional
	public IncidentResponse transitionIncident(Long incidentId, TransitionRequest request, Long actorId) {
		if (request == null || request.expectedVersion() == null) {
			throw new FieldValidationException("expectedVersion", "The incident version you saw is required");
		}
		if (request.status() == null) {
			throw new FieldValidationException("status", "Status is required");
		}

		Incident incident = requireIncident(incidentId);
		requireVersion(incident, request.expectedVersion());

		IncidentStatus currentStatus = incident.getStatus();
		IncidentStatus newStatus = request.status();

		// OPEN -> ACKNOWLEDGED is legal, but only through POST /acknowledge (#83), which makes the acknowledger the
		// Lead, stops the response clock, and cancels escalation; one path keeps those rules in one place.
		if (newStatus == IncidentStatus.ACKNOWLEDGED) {
			throw new ConflictException("Acknowledge the incident instead");
		}

		if (!transitionPolicy.isLegal(currentStatus, newStatus)) {
			throw new ConflictException("Cannot transition incident from " + currentStatus + " to " + newStatus);
		}

		if (newStatus == IncidentStatus.RESOLVED) {
			if (request.note() == null || request.note().isBlank()) {
				throw new FieldValidationException("note", "A recovery note is required to resolve an incident");
			}
		}

		AppUser actor = requireActor(actorId);
		Instant now = clock.instant();

		incident.transitionTo(newStatus, now);

		try {
			incident = incidentRepository.saveAndFlush(incident);
		} catch (OptimisticLockingFailureException ex) {
			throw new ConflictException(STALE_CHANGE);
		}

		// The note goes on the timeline with any move, not only RESOLVED.
		String transitionDetails = "Status changed from " + currentStatus + " to " + newStatus;
		if (request.note() != null && !request.note().isBlank()) {
			transitionDetails += ": " + request.note().trim();
		}

		TimelineEntry statusTimelineEntry = new TimelineEntry(
				incident,
				actor,
				TimelineEventType.STATUS_CHANGED,
				transitionDetails,
				now
		);
		timelineEntryRepository.save(statusTimelineEntry);

		List<TimelineEntry> updatedTimeline = timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId);
		return toResponse(incident, updatedTimeline);
	}

	/**
	 * Changes an incident's severity with a reason (FR-04.2). The SLA targets and due times copied at creation stay as
	 * they were; the change and its reason go on the timeline.
	 */
	@Transactional
	public IncidentResponse changeSeverity(Long incidentId, SeverityChangeRequest request, Long actorId) {
		Incident incident = requireIncident(incidentId);
		requireVersion(incident, request.expectedVersion());
		if (incident.getStatus() == IncidentStatus.CLOSED) {
			throw new ConflictException("Cannot change severity on a closed incident");
		}
		Severity previous = incident.getSeverity();
		if (previous == request.severity()) {
			throw new ConflictException("The incident is already " + previous);
		}
		AppUser actor = requireActor(actorId);
		Instant now = clock.instant();
		incident.changeSeverity(request.severity());
		try {
			incident = incidentRepository.saveAndFlush(incident);
		}
		catch (OptimisticLockingFailureException ex) {
			throw new ConflictException(STALE_CHANGE);
		}
		timelineEntryRepository.save(new TimelineEntry(incident, actor, TimelineEventType.SEVERITY_CHANGED,
				"Severity changed from " + previous + " to " + request.severity() + ": " + request.reason().trim(), now));
		return toResponse(incident, timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId));
	}

	/** Reassigns an active incident, records a timeline entry, and notifies the new assignee. */
	@Transactional
	public IncidentResponse reassignIncident(Long incidentId, ReassignRequest request, Long actorId) {
		if (request == null || request.assigneeId() == null) {
			throw new FieldValidationException("assigneeId", "Assignee is required");
		}

		Incident incident = requireIncident(incidentId);
		requireVersion(incident, request.expectedVersion());

		if (incident.getStatus() == IncidentStatus.CLOSED) {
			throw new ConflictException("Cannot reassign a closed incident");
		}

		Membership assigneeMember = orgMembers.find(request.assigneeId())
				.orElseThrow(() -> new FieldValidationException("assigneeId", "Assignee user not found"));

		if (!assigneeMember.isEnabled()) {
			throw new FieldValidationException("assigneeId", "Assignee must be an enabled user");
		}

		AppUser newAssignee = assigneeMember.getUser();

		if (incident.getAssignee() != null && incident.getAssignee().getId().equals(newAssignee.getId())) {
			throw new FieldValidationException("assigneeId", "Already assigned to this person");
		}

		AppUser actor = requireActor(actorId);

		String previousName = incident.getAssignee() != null ? incident.getAssignee().getDisplayName() : "unassigned";
		String newName = newAssignee.getDisplayName();

		incident.assignTo(newAssignee);
		try {
			incident = incidentRepository.saveAndFlush(incident);
		}
		catch (OptimisticLockingFailureException ex) {
			throw new ConflictException(STALE_CHANGE);
		}

		Instant now = clock.instant();
		String details = "Reassigned from " + previousName + " to " + newName;
		TimelineEntry timelineEntry = new TimelineEntry(
				incident,
				actor,
				TimelineEventType.REASSIGNED,
				details,
				now
		);
		timelineEntryRepository.save(timelineEntry);

		notificationService.createNotification(
				newAssignee,
				incident,
				"You have been assigned to incident " + incident.getReference() + ": " + incident.getTitle()
		);

		List<TimelineEntry> timeline = timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId);
		return toResponse(incident, timeline);
	}

	/**
	 * Gives Comms or Ops to an enabled member of the org (D39, FR-05.4). The Lead is the assignee, so
	 * it changes through {@link #reassignIncident}. Never restarts a clock. The response carries the
	 * FR-05.5 warning when one person now holds more than one role on a SEV1 or SEV2 incident.
	 */
	@Transactional
	public IncidentResponse assignRole(Long incidentId, AssignRoleRequest request, Long actorId) {
		if (request.role() == IncidentRole.LEAD) {
			throw new FieldValidationException("role",
					"The Lead is the assignee: change it with PUT /api/incidents/" + incidentId + "/assignee");
		}

		Incident incident = requireIncident(incidentId);
		requireVersion(incident, request.expectedVersion());

		if (incident.getStatus() == IncidentStatus.CLOSED) {
			throw new ConflictException("Cannot change roles on a closed incident");
		}

		Membership targetMember = orgMembers.find(request.userId())
				.orElseThrow(() -> new FieldValidationException("userId", "User not found"));

		if (!targetMember.isEnabled()) {
			throw new FieldValidationException("userId", "User must be an enabled member");
		}

		AppUser newRoleUser = targetMember.getUser();
		AppUser actor = requireActor(actorId);

		AppUser previousUser = incident.getRoleUser(request.role());
		if (previousUser != null && previousUser.getId().equals(newRoleUser.getId())) {
			throw new FieldValidationException("userId", "Already assigned to this role");
		}

		String previousName = previousUser != null ? previousUser.getDisplayName() : null;

		incident.assignRole(request.role(), newRoleUser);

		try {
			incident = incidentRepository.saveAndFlush(incident);
		}
		catch (OptimisticLockingFailureException ex) {
			throw new ConflictException(STALE_CHANGE);
		}

		Instant now = clock.instant();
		String details = previousUser == null
				? "Assigned " + request.role() + " to " + newRoleUser.getDisplayName()
				: "Assigned " + request.role() + " from " + previousName + " to " + newRoleUser.getDisplayName();

		TimelineEntry timelineEntry = new TimelineEntry(
				incident,
				actor,
				TimelineEventType.ROLE_ASSIGNED,
				details,
				now
		);
		timelineEntryRepository.save(timelineEntry);

		notificationService.createNotification(
				newRoleUser,
				incident,
				"You have been assigned the role " + request.role() + " on incident " + incident.getReference() + ": " + incident.getTitle()
		);

		List<TimelineEntry> timeline = timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId);
		return toResponse(incident, timeline);
	}

	/**
	 * Refuses a change made from an older view of the incident, rather than applying it over a change
	 * committed since (NFR-11.2, architecture.md section 7). The row's own version check still catches
	 * two changes that overlap in time.
	 */
	private static void requireVersion(Incident incident, long expectedVersion) {
		if (incident.getVersion() != expectedVersion) {
			throw new ConflictException(STALE_CHANGE);
		}
	}

	private Incident requireIncident(Long incidentId) {
		return incidentRepository.findById(incidentId)
				.orElseThrow(() -> new NotFoundException("Incident " + incidentId + " not found"));
	}

	private Incident requireIncidentForUpdate(Long incidentId) {
		return incidentRepository.findByIdForUpdate(incidentId)
				.orElseThrow(() -> new NotFoundException("Incident " + incidentId + " not found"));
	}

	private AppUser requireActor(Long actorId) {
		return appUserRepository.findById(actorId)
				.orElseThrow(() -> new NotFoundException("User " + actorId + " not found"));
	}

	private static TimelineEntryResponse toTimelineEntryResponse(TimelineEntry entry) {
		return new TimelineEntryResponse(
				entry.getId(),
				entry.getEventType(),
				entry.getActor() != null ? entry.getActor().getDisplayName() : null,
				entry.getDetails(),
				entry.getRecordedAt(),
				entry.getCorrectsEntry() != null ? entry.getCorrectsEntry().getId() : null
		);
	}
}
