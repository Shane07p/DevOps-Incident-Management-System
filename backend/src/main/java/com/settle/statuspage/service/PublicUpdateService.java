package com.settle.statuspage.service;

import com.settle.auth.entity.AppUser;
import com.settle.auth.entity.Role;
import com.settle.common.error.ConflictException;
import com.settle.common.error.NotFoundException;
import com.settle.incident.entity.Incident;
import com.settle.incident.entity.TimelineEntry;
import com.settle.incident.entity.TimelineEventType;
import com.settle.incident.repository.IncidentRepository;
import com.settle.incident.repository.TimelineEntryRepository;
import com.settle.org.entity.Membership;
import com.settle.org.service.OrgMembers;
import com.settle.statuspage.dto.PublicUpdateRequest;
import com.settle.statuspage.dto.PublicUpdateResponse;
import com.settle.statuspage.entity.PublicUpdate;
import com.settle.statuspage.repository.PublicUpdateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PublicUpdateService {

	private static final String NOT_ALLOWED = "Only the incident's Lead or Comms, or an Admin, publishes public updates";

	private final IncidentRepository incidentRepository;

	private final PublicUpdateRepository publicUpdateRepository;

	private final TimelineEntryRepository timelineEntryRepository;

	private final OrgMembers orgMembers;

	private final Clock clock;

	/**
	 * Publishes a customer-facing update (FR-21.2, D45) and records who published it on the timeline. Like a
	 * comment, it is a separate insert: it neither needs nor changes the incident version.
	 */
	@Transactional
	public PublicUpdateResponse publish(Long incidentId, PublicUpdateRequest request, Long actorId) {
		Incident incident = incidentRepository.findById(incidentId)
				.orElseThrow(() -> new NotFoundException("Incident " + incidentId + " not found"));
		Membership actor = orgMembers.find(actorId)
				.filter(Membership::isEnabled)
				.orElseThrow(() -> new AccessDeniedException(NOT_ALLOWED));
		if (!mayPublish(incident, actor)) {
			throw new AccessDeniedException(NOT_ALLOWED);
		}
		if (!incident.getService().isPublicVisible()) {
			throw new ConflictException("This incident's service is not on the public status page; an Admin can add it");
		}

		// PostgreSQL keeps microseconds; the response then shows the same time the page will.
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		String message = request.message().strip();
		PublicUpdate update = publicUpdateRepository.save(
				new PublicUpdate(incident, request.status(), message, actor.getUser(), now));
		timelineEntryRepository.save(new TimelineEntry(incident, actor.getUser(), TimelineEventType.PUBLIC_UPDATE,
				"Public update (" + request.status() + "): " + message, now));
		return PublicUpdateResponse.from(update);
	}

	/** The incident's Lead (its assignee, D39) or Comms, or any Admin of the org (D45). */
	static boolean mayPublish(Incident incident, Membership actor) {
		if (actor.getRole() == Role.ADMIN) {
			return true;
		}
		Long userId = actor.getUser().getId();
		return holds(incident.getAssignee(), userId) || holds(incident.getComms(), userId);
	}

	private static boolean holds(AppUser roleHolder, Long userId) {
		return roleHolder != null && roleHolder.getId().equals(userId);
	}
}
