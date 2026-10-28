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
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EscalationService {

	private final EscalationPolicyRepository escalationPolicyRepository;
	private final EscalationTaskRepository escalationTaskRepository;
	private final TimelineEntryRepository timelineEntryRepository;
	private final OutboxRepository outboxRepository;
	private final EntityManager entityManager;
	private final Clock clock;

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

	public int processOverdueEscalations() {
		Instant now = clock.instant();
		List<Long> taskIds = escalationTaskRepository.findOverdueTaskIds(EscalationTaskState.PENDING, now);
		int processed = 0;

		for (Long taskId : taskIds) {
			try {
				boolean executed = escalateTaskInNewTransaction(taskId, now);
				if (executed) {
					processed++;
				}
			}
			catch (OptimisticLockException | OptimisticLockingFailureException e) {
				log.info("Optimistic lock race detected during escalation for task {}. Loser rolled back.", taskId);
			}
			catch (Exception e) {
				log.error("Failed to escalate task {}", taskId, e);
			}
		}

		return processed;
	}

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

		// Rule E4: If incident is not OPEN (e.g. RESOLVED, CLOSED) -> cancel task and do nothing
		if (incident.getStatus() != IncidentStatus.OPEN) {
			task.cancel(now);
			return false;
		}

		// Rule E3: If incident was acknowledged -> cancel task and do nothing
		if (incident.getStatus() == IncidentStatus.ACKNOWLEDGED) {
			task.cancel(now);
			return false;
		}

		// Rule E2: If deadline has not passed -> do nothing
		if (task.getNextDueAt() == null || task.getNextDueAt().isAfter(now)) {
			return false;
		}

		// Force version increment on Incident to guarantee optimistic locking race detection (TC-FR-07.4a)
		entityManager.lock(incident, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
		entityManager.flush();

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
			String payload = """
					{"incidentId":%d,"incidentRef":"%s","stepNumber":%d,"responderId":%d,"responderName":"%s","escalatedAt":"%s"}"""
					.formatted(incident.getId(), incident.getReference(), nextStep.getStepNumber(),
							responder.getId(), responder.getDisplayName(), now);

			Outbox outbox = new Outbox("INCIDENT", incident.getId(), "INCIDENT_ESCALATED", payload, now);
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

					String payload = """
							{"incidentId":%d,"incidentRef":"%s","fallbackContactId":%d,"fallbackContactName":"%s","escalatedAt":"%s"}"""
							.formatted(incident.getId(), incident.getReference(),
									fallbackContact.getId(), fallbackContact.getDisplayName(), now);

					Outbox outbox = new Outbox("INCIDENT", incident.getId(), "INCIDENT_ESCALATED_FALLBACK", payload, now);
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
}
