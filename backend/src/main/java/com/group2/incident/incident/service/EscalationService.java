package com.group2.incident.incident.service;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.incident.entity.EscalationPolicy;
import com.group2.incident.incident.entity.EscalationStep;
import com.group2.incident.incident.entity.EscalationTask;
import com.group2.incident.incident.entity.EscalationTaskState;
import com.group2.incident.incident.entity.EscalationTaskStep;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.Outbox;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.EscalationPolicyRepository;
import com.group2.incident.incident.repository.EscalationTaskRepository;
import com.group2.incident.incident.repository.OutboxRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class EscalationService {

	private final EscalationPolicyRepository escalationPolicyRepository;
	private final EscalationTaskRepository escalationTaskRepository;
	private final TimelineEntryRepository timelineEntryRepository;
	private final OutboxRepository outboxRepository;
	private final EntityManager entityManager;
	private final JsonMapper jsonMapper;

	@Transactional
	public void createTaskForIncident(Incident incident, Instant now) {
		Long serviceId = incident.getService().getId();
		Optional<EscalationPolicy> policyOpt = escalationPolicyRepository.findByServiceId(serviceId);
		if (policyOpt.isEmpty()) {
			return;
		}

		EscalationPolicy policy = policyOpt.get();
		List<EscalationStep> steps = policy.getSteps();
		if (steps.isEmpty()) {
			return;
		}

		EscalationStep firstStep = steps.stream()
				.min(Comparator.comparingInt(EscalationStep::getStepNumber))
				.orElseThrow();

		Instant nextDueAt = now.plus(firstStep.getWaitMinutes(), ChronoUnit.MINUTES);
		EscalationTask task = new EscalationTask(incident, nextDueAt, now);

		for (EscalationStep step : steps) {
			task.addCopiedStep(step.getStepNumber(), step.getResponder(), step.getWaitMinutes());
		}

		escalationTaskRepository.save(task);
	}

	@Transactional
	public void cancelTaskForIncident(Long incidentId, Instant now) {
		escalationTaskRepository.findByIncidentId(incidentId).ifPresent(task -> {
			if (task.getState() == EscalationTaskState.PENDING) {
				task.cancel(now);
			}
		});
	}

	/** For EscalationWorker: each task commits on its own, so one failure or lost race doesn't stop the rest. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean escalateTaskInNewTransaction(Long taskId, Instant now) {
		return executeEscalationStep(taskId, now);
	}

	@Transactional
	public boolean executeEscalationStep(Long taskId, Instant now) {
		EscalationTask task = escalationTaskRepository.findById(taskId).orElse(null);
		if (task == null || task.getState() != EscalationTaskState.PENDING) {
			return false;
		}

		Incident incident = task.getIncident();

		// Rules E3 and E4: an acknowledged, resolved, or closed incident is no longer escalated.
		if (incident.getStatus() != IncidentStatus.OPEN) {
			task.cancel(now);
			return false;
		}

		// Rule E2: If deadline has not passed -> do nothing
		if (task.getNextDueAt() == null || task.getNextDueAt().isAfter(now)) {
			return false;
		}

		// The incident version goes up at commit, so an acknowledgement racing this step loses and
		// rolls back (TC-FR-07.4a). The assignee is never changed.
		entityManager.lock(incident, LockModeType.OPTIMISTIC_FORCE_INCREMENT);

		int currentStep = task.getCurrentStep();
		int nextStepNumber = currentStep + 1;

		List<EscalationTaskStep> steps = task.getSteps();
		Optional<EscalationTaskStep> nextStepOpt = steps.stream()
				.filter(s -> s.getStepNumber() == nextStepNumber)
				.findFirst();

		if (nextStepOpt.isPresent()) {
			// Rule E1: Next level exists -> notify next level, advance step, calculate next deadline
			EscalationTaskStep nextStep = nextStepOpt.get();
			AppUser responder = nextStep.getResponder();

			// Timeline entry (system action, actor is null)
			String details = "Escalated to level " + nextStep.getStepNumber() + ": " + responder.getDisplayName();
			TimelineEntry timelineEntry = new TimelineEntry(
					incident,
					null,
					TimelineEventType.ESCALATED,
					details,
					now
			);
			timelineEntryRepository.save(timelineEntry);

			// Outbox event for RabbitMQ
			Map<String, Object> event = eventFor(incident, now);
			event.put("stepNumber", nextStep.getStepNumber());
			event.put("responderId", responder.getId());
			event.put("responderName", responder.getDisplayName());

			Outbox outbox = new Outbox("INCIDENT", incident.getId(), "INCIDENT_ESCALATED", jsonMapper.writeValueAsString(event), now);
			outboxRepository.save(outbox);

			// Calculate next deadline
			Optional<EscalationTaskStep> subsequentStepOpt = steps.stream()
					.filter(s -> s.getStepNumber() == nextStepNumber + 1)
					.findFirst();

			int waitMinutes = subsequentStepOpt.map(EscalationTaskStep::getWaitMinutes)
					.orElse(nextStep.getWaitMinutes());

			Instant nextDueAt = now.plus(waitMinutes, ChronoUnit.MINUTES);
			task.advanceToStep(nextStep.getStepNumber(), nextDueAt, now);

			return true;
		}
		else {
			// Rule E5: All configured levels exhausted -> notify fallback contact once
			if (!task.isFallbackNotified()) {
				AppUser fallbackContact = incident.getService().getFallbackContact();

				if (fallbackContact != null) {
					String details = "Escalated to fallback contact: " + fallbackContact.getDisplayName() + " (all levels exhausted)";
					TimelineEntry timelineEntry = new TimelineEntry(
							incident,
							null,
							TimelineEventType.ESCALATED,
							details,
							now
					);
					timelineEntryRepository.save(timelineEntry);

					Map<String, Object> event = eventFor(incident, now);
					event.put("fallbackContactId", fallbackContact.getId());
					event.put("fallbackContactName", fallbackContact.getDisplayName());

					Outbox outbox = new Outbox("INCIDENT", incident.getId(), "INCIDENT_ESCALATED_FALLBACK",
							jsonMapper.writeValueAsString(event), now);
					outboxRepository.save(outbox);
				}
				else {
					String details = "All escalation levels exhausted (no fallback contact configured)";
					TimelineEntry timelineEntry = new TimelineEntry(
							incident,
							null,
							TimelineEventType.ESCALATED,
							details,
							now
					);
					timelineEntryRepository.save(timelineEntry);
				}

				task.markFallbackNotified(now);
				return true;
			}
			else {
				task.markExhausted(now);
				return false;
			}
		}
	}

	private static Map<String, Object> eventFor(Incident incident, Instant now) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("incidentId", incident.getId());
		event.put("incidentRef", incident.getReference());
		event.put("escalatedAt", now.toString());
		return event;
	}
}
