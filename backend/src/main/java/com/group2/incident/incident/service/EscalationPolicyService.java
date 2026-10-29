package com.group2.incident.incident.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.incident.dto.EscalationPolicyResponse;
import com.group2.incident.incident.dto.EscalationStepRequest;
import com.group2.incident.incident.dto.UpdateEscalationPolicyRequest;
import com.group2.incident.incident.entity.EscalationPolicy;
import com.group2.incident.incident.entity.EscalationStep;
import com.group2.incident.incident.repository.EscalationPolicyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EscalationPolicyService {

	private final EscalationPolicyRepository escalationPolicyRepository;
	private final ServiceRepository serviceRepository;
	private final AppUserRepository appUserRepository;
	private final AuditService auditService;
	private final Clock clock;

	@Transactional(readOnly = true)
	public EscalationPolicyResponse getPolicy(Long serviceId) {
		serviceRepository.findById(serviceId)
				.orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));

		EscalationPolicy policy = escalationPolicyRepository.findByServiceId(serviceId)
				.orElseThrow(() -> new NotFoundException("Escalation policy not found for service " + serviceId));

		return EscalationPolicyResponse.from(policy);
	}

	@Transactional
	public EscalationPolicyResponse updatePolicy(Long serviceId, UpdateEscalationPolicyRequest request, Long actorId) {
		ServiceEntity service = serviceRepository.findById(serviceId)
				.orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));

		AppUser actor = null;
		if (actorId != null) {
			actor = appUserRepository.findById(actorId).orElse(null);
		}

		Instant now = clock.instant();
		List<EscalationStepRequest> stepRequests = request.steps();
		if (stepRequests == null || stepRequests.isEmpty()) {
			throw new FieldValidationException("steps", "Escalation policy must have at least one step");
		}

		validateSteps(stepRequests);

		List<EscalationStep> steps = new ArrayList<>();
		for (EscalationStepRequest sr : stepRequests) {
			AppUser responder = appUserRepository.findById(sr.responderId())
					.orElseThrow(() -> new FieldValidationException("steps", "Responder user " + sr.responderId() + " not found"));

			if (!responder.isEnabled()) {
				throw new FieldValidationException("steps", "Responder user " + responder.getUsername() + " is disabled");
			}

			steps.add(new EscalationStep(sr.stepNumber(), responder, sr.waitMinutes()));
		}

		steps.sort(Comparator.comparingInt(EscalationStep::getStepNumber));

		EscalationPolicy policy = escalationPolicyRepository.findByServiceId(serviceId)
				.orElseGet(() -> new EscalationPolicy(service, now));

		policy.replaceSteps(steps, now);
		policy = escalationPolicyRepository.save(policy);

		auditService.record(
				actor,
				AuditAction.ESCALATION_POLICY_UPDATED,
				AuditItemType.SERVICE,
				serviceId,
				"Updated escalation policy with " + steps.size() + " steps for service " + service.getName()
		);

		return EscalationPolicyResponse.from(policy);
	}

	private void validateSteps(List<EscalationStepRequest> steps) {
		List<EscalationStepRequest> sorted = steps.stream()
				.sorted(Comparator.comparing(EscalationStepRequest::stepNumber))
				.toList();

		Set<Integer> seenNumbers = new HashSet<>();
		for (int i = 0; i < sorted.size(); i++) {
			EscalationStepRequest step = sorted.get(i);
			int expectedStepNumber = i + 1;
			if (step.stepNumber() == null || step.stepNumber() != expectedStepNumber) {
				throw new FieldValidationException("steps", "Step numbers must be sequential starting at 1; expected " + expectedStepNumber + " but got " + step.stepNumber());
			}
			if (!seenNumbers.add(step.stepNumber())) {
				throw new FieldValidationException("steps", "Duplicate step number: " + step.stepNumber());
			}
			if (step.waitMinutes() == null || step.waitMinutes() <= 0) {
				throw new FieldValidationException("steps", "Wait minutes must be greater than zero");
			}
		}
	}
}
