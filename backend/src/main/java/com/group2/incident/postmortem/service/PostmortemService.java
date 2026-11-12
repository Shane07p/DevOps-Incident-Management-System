package com.group2.incident.postmortem.service;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.postmortem.dto.PostmortemResponse;
import com.group2.incident.postmortem.dto.PostmortemRequest;
import com.group2.incident.postmortem.entity.Postmortem;
import com.group2.incident.postmortem.entity.PostmortemStatus;
import com.group2.incident.postmortem.repository.PostmortemRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostmortemService {

	private static final String STALE = "This record was changed by another request; reload and try again";

	private final PostmortemRepository postmortems;
	private final IncidentRepository incidents;
	private final AppUserRepository users;
	private final TimelineEntryRepository timeline;
	private final Clock clock;

	@Transactional(readOnly = true)
	public PostmortemResponse get(Long incidentId) {
		requireIncident(incidentId);
		return PostmortemResponse.from(requirePostmortem(incidentId));
	}

	@Transactional
	public PostmortemResponse save(Long incidentId, PostmortemRequest request, Long actorId) {
		Incident incident = requireIncident(incidentId);
		Postmortem postmortem = postmortems.findByIncidentId(incidentId).orElse(null);
		boolean creating = postmortem == null;
		Long currentVersion = creating ? null : postmortem.getVersion();
		if (!Objects.equals(currentVersion, request.expectedVersion())) {
			throw new ConflictException(STALE);
		}
		AppUser actor = requireActor(actorId);
		Instant now = clock.instant();
		if (creating) {
			postmortem = new Postmortem(incident, request.summary(), request.impact(), request.rootCause(),
					request.resolution(), request.wentWell(), request.wentBadly(), actor, now);
		} else {
			postmortem.draft(request.summary(), request.impact(), request.rootCause(), request.resolution(),
					request.wentWell(), request.wentBadly(), actor, now);
		}
		try {
			return PostmortemResponse.from(postmortems.saveAndFlush(postmortem));
		} catch (DataIntegrityViolationException ex) {
			// Two first drafts may both see no row: the unique incident constraint chooses the winner.
			if (creating && ex.getMostSpecificCause() instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
				throw new ConflictException(STALE);
			}
			throw ex;
		}
	}

	@Transactional
	public PostmortemResponse review(Long incidentId, long expectedVersion, Long actorId) {
		Incident incident = requireIncident(incidentId);
		Postmortem postmortem = requirePostmortem(incidentId);
		if (postmortem.getVersion() != expectedVersion) {
			throw new ConflictException(STALE);
		}
		if (postmortem.getAuthor().getId().equals(actorId)) {
			throw new ConflictException("Someone other than the author must review the postmortem");
		}
		if (postmortem.getStatus() == PostmortemStatus.REVIEWED) {
			return PostmortemResponse.from(postmortem);
		}
		AppUser actor = requireActor(actorId);
		Instant now = clock.instant();
		postmortem.review(actor, now);
		postmortems.saveAndFlush(postmortem);
		timeline.save(new TimelineEntry(incident, actor, TimelineEventType.POSTMORTEM_REVIEWED,
				"Postmortem reviewed by " + actor.getDisplayName(), now));
		return PostmortemResponse.from(postmortem);
	}

	/** Called by #84's RESOLVED-to-CLOSED transition, in the same transaction (D17). */
	@Transactional(readOnly = true)
	public void requireReviewedForClosure(Incident incident) {
		if ((incident.getSeverity() == Severity.SEV1 || incident.getSeverity() == Severity.SEV2)
				&& postmortems.findByIncidentId(incident.getId())
						.filter(postmortem -> postmortem.getStatus() == PostmortemStatus.REVIEWED).isEmpty()) {
			throw new ConflictException("A reviewed postmortem is required to close a SEV1 or SEV2 incident");
		}
	}

	private Incident requireIncident(Long incidentId) {
		return incidents.findById(incidentId)
				.orElseThrow(() -> new NotFoundException("Incident " + incidentId + " not found"));
	}

	private Postmortem requirePostmortem(Long incidentId) {
		return postmortems.findByIncidentId(incidentId)
				.orElseThrow(() -> new NotFoundException("Postmortem for incident " + incidentId + " not found"));
	}

	private AppUser requireActor(Long actorId) {
		// The id is the authenticated token's subject, never an id chosen in a request body.
		return users.findById(actorId).orElseThrow(() -> new NotFoundException("User " + actorId + " not found"));
	}
}
