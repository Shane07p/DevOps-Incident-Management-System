package com.group2.incident.incident.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.SlaTarget;
import com.group2.incident.catalog.service.OnCallService;
import com.group2.incident.catalog.service.ServiceCatalogService;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.dto.IncidentServiceDto;
import com.group2.incident.incident.dto.IncidentSummaryResponse;
import com.group2.incident.incident.dto.IncidentUserDto;
import com.group2.incident.incident.dto.ReassignRequest;
import com.group2.incident.incident.dto.TimelineEntryResponse;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.notification.service.NotificationService;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.OrganizationRepository;
import com.group2.incident.org.service.OrgMembers;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncidentService {

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
		return toResponse(incident, timeline);
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
						i.getCreatedAt()
				))
				.toList();
	}

	/** Maps an incident and its append-only timeline, including correction links, to API data. */
	private static IncidentResponse toResponse(Incident incident, List<TimelineEntry> timeline) {
		IncidentServiceDto serviceDto = new IncidentServiceDto(
				incident.getService().getId(),
				incident.getService().getName()
		);
		IncidentUserDto reporterDto = incident.getReporter() != null
				? new IncidentUserDto(incident.getReporter().getId(), incident.getReporter().getDisplayName())
				: null;
		IncidentUserDto assigneeDto = incident.getAssignee() != null
				? new IncidentUserDto(incident.getAssignee().getId(), incident.getAssignee().getDisplayName())
				: null;

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
				reporterDto,
				assigneeDto,
				incident.getCreatedAt(),
				incident.getResponseTargetMinutes(),
				incident.getResolutionTargetMinutes(),
				incident.getResponseDueAt(),
				incident.getResolutionDueAt(),
				timelineResponses
		);
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

	/** Reassigns an active incident, records a timeline entry, and notifies the new assignee. */
	@Transactional
	public IncidentResponse reassignIncident(Long incidentId, ReassignRequest request, Long actorId) {
		if (request == null || request.assigneeId() == null) {
			throw new FieldValidationException("assigneeId", "Assignee is required");
		}

		Incident incident = requireIncident(incidentId);

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
			throw new ConflictException("This record was changed by another request; reload and try again");
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

	private Incident requireIncident(Long incidentId) {
		return incidentRepository.findById(incidentId)
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
