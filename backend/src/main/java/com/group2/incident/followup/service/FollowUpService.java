package com.group2.incident.followup.service;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.followup.dto.CreateFollowUpRequest;
import com.group2.incident.followup.dto.FollowUpResponse;
import com.group2.incident.followup.dto.UpdateFollowUpRequest;
import com.group2.incident.followup.entity.FollowUp;
import com.group2.incident.followup.entity.FollowUpKind;
import com.group2.incident.followup.entity.FollowUpStatus;
import com.group2.incident.followup.repository.FollowUpRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.service.OrgMembers;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Follow-ups (#240, FR-11.3). Like comments, they are rows of their own: adding, editing, or finishing one
 * neither needs nor changes the incident's version, and the incident's status does not limit them (D17).
 */
@Service
@RequiredArgsConstructor
public class FollowUpService {

	private static final String OWNER_NOT_A_MEMBER = "The owner must be an active member of this org";

	private static final String DUE_DATE_IN_THE_PAST = "The due date cannot be before today (UTC)";

	private final FollowUpRepository followUpRepository;

	private final IncidentRepository incidentRepository;

	private final TimelineEntryRepository timelineEntryRepository;

	private final OrgMembers orgMembers;

	private final Clock clock;

	/** The incident's follow-ups, oldest first. */
	@Transactional(readOnly = true)
	public List<FollowUpResponse> listForIncident(Long incidentId) {
		findIncident(incidentId);
		LocalDate today = today();
		return followUpRepository.findAllByIncidentIdOrderByCreatedAtAscIdAsc(incidentId).stream()
				.map(followUp -> FollowUpResponse.from(followUp, today))
				.toList();
	}

	/**
	 * The org's follow-ups across incidents, soonest due first. Each filter is optional: {@code ownerId} keeps one
	 * owner's, {@code status} one status, and {@code overdue} only overdue ones (true) or only the rest (false).
	 */
	@Transactional(readOnly = true)
	public List<FollowUpResponse> list(Long ownerId, FollowUpStatus status, Boolean overdue) {
		LocalDate today = today();
		Specification<FollowUp> filter = (root, query, cb) -> cb.conjunction();
		if (ownerId != null) {
			filter = filter.and((root, query, cb) -> cb.equal(root.get("owner").get("id"), ownerId));
		}
		if (status != null) {
			filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
		}
		if (overdue != null) {
			filter = filter.and(overdue ? overdueOn(today) : Specification.not(overdueOn(today)));
		}
		return followUpRepository.findAll(filter, Sort.by("dueDate", "id")).stream()
				.map(followUp -> FollowUpResponse.from(followUp, today))
				.toList();
	}

	/** Adds a follow-up and says so on the incident's timeline. */
	@Transactional
	public FollowUpResponse create(Long incidentId, CreateFollowUpRequest request, Long actorId) {
		Incident incident = findIncident(incidentId);
		AppUser owner = findOwner(request.ownerId());
		LocalDate today = today();
		requireNotBefore(request.dueDate(), today);

		// PostgreSQL keeps microseconds; the response then shows the same time a later read will.
		Instant now = now();
		FollowUp followUp = followUpRepository.save(
				new FollowUp(incident, request.title().strip(), request.kind(), owner, request.dueDate(), now));
		record(incident, actorId, TimelineEventType.FOLLOW_UP_ADDED, "Follow-up added (%s): %s. Owner %s, due %s".formatted(
				label(followUp.getKind()), followUp.getTitle(), owner.getDisplayName(), followUp.getDueDate()), now);
		return FollowUpResponse.from(followUp, today);
	}

	/**
	 * Changes the fields the request names. A due date that is not changed may already be in the past, so an
	 * overdue follow-up can still be renamed or finished; a new due date cannot be before today. Becoming DONE
	 * records when, and says so on the timeline.
	 */
	@Transactional
	public FollowUpResponse update(Long followUpId, UpdateFollowUpRequest request, Long actorId) {
		FollowUp followUp = followUpRepository.findById(followUpId)
				.orElseThrow(() -> new NotFoundException("Follow-up " + followUpId + " not found"));
		LocalDate today = today();
		Instant now = now();

		if (request.title() != null) {
			followUp.rename(request.title().strip());
		}
		if (request.kind() != null) {
			followUp.changeKind(request.kind());
		}
		if (request.ownerId() != null) {
			followUp.handTo(findOwner(request.ownerId()));
		}
		if (request.dueDate() != null && !request.dueDate().equals(followUp.getDueDate())) {
			requireNotBefore(request.dueDate(), today);
			followUp.moveDueDate(request.dueDate());
		}
		if (request.status() != null) {
			boolean finishing = request.status() == FollowUpStatus.DONE && followUp.getStatus() != FollowUpStatus.DONE;
			followUp.moveTo(request.status(), now);
			if (finishing) {
				record(followUp.getIncident(), actorId, TimelineEventType.FOLLOW_UP_DONE,
						"Follow-up done: " + followUp.getTitle(), now);
			}
		}
		return FollowUpResponse.from(followUp, today);
	}

	private Incident findIncident(Long incidentId) {
		return incidentRepository.findById(incidentId)
				.orElseThrow(() -> new NotFoundException("Incident " + incidentId + " not found"));
	}

	// Users are global (D48): someone outside the org is refused like an id that does not exist.
	private AppUser findOwner(Long ownerId) {
		return orgMembers.find(ownerId)
				.filter(Membership::isEnabled)
				.map(Membership::getUser)
				.orElseThrow(() -> new FieldValidationException("ownerId", OWNER_NOT_A_MEMBER));
	}

	private void record(Incident incident, Long actorId, TimelineEventType type, String details, Instant now) {
		AppUser actor = orgMembers.find(actorId).map(Membership::getUser).orElse(null);
		timelineEntryRepository.save(new TimelineEntry(incident, actor, type, details, now));
	}

	private static void requireNotBefore(LocalDate dueDate, LocalDate today) {
		if (dueDate.isBefore(today)) {
			throw new FieldValidationException("dueDate", DUE_DATE_IN_THE_PAST);
		}
	}

	private static Specification<FollowUp> overdueOn(LocalDate today) {
		return (root, query, cb) -> cb.and(
				cb.notEqual(root.get("status"), FollowUpStatus.DONE),
				cb.lessThan(root.get("dueDate"), today));
	}

	private static String label(FollowUpKind kind) {
		String name = kind.name().toLowerCase(Locale.ROOT);
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}
}
