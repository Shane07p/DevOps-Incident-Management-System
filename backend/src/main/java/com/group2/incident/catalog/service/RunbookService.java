package com.group2.incident.catalog.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.dto.RunbookRequest;
import com.group2.incident.catalog.dto.RunbookResponse;
import com.group2.incident.catalog.entity.Runbook;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.RunbookRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.NotFoundException;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runbooks per service (FR-25): everyone reads them, Engineers and Admins write them, every change is audited. */
@Service
@RequiredArgsConstructor
public class RunbookService {

	private final RunbookRepository runbookRepository;
	private final ServiceRepository serviceRepository;
	private final AppUserRepository appUserRepository;
	private final AuditService auditService;
	private final Clock clock;

	@Transactional(readOnly = true)
	public List<RunbookResponse> list(Long serviceId) {
		requireService(serviceId);
		return runbookRepository.findAllByServiceIdOrderByTitleAscIdAsc(serviceId).stream().map(RunbookResponse::from).toList();
	}

	@Transactional
	public RunbookResponse create(Long serviceId, RunbookRequest request, Long actorId) {
		ServiceEntity service = requireService(serviceId);
		Runbook runbook = runbookRepository.save(new Runbook(service, request.title().strip(), stripped(request.steps()), clock.instant()));
		auditService.record(actor(actorId), AuditAction.RUNBOOK_CREATED, AuditItemType.RUNBOOK, runbook.getId(), describe(runbook));
		return RunbookResponse.from(runbook);
	}

	/** A save that changes nothing writes no audit entry, as with services and monitors. */
	@Transactional
	public RunbookResponse update(Long id, RunbookRequest request, Long actorId) {
		Runbook runbook = requireRunbook(id);
		String title = request.title().strip();
		List<String> steps = stripped(request.steps());
		if (runbook.getTitle().equals(title) && runbook.getSteps().equals(steps)) {
			return RunbookResponse.from(runbook);
		}
		String before = describe(runbook);
		runbook.change(title, steps, clock.instant());
		auditService.record(actor(actorId), AuditAction.RUNBOOK_UPDATED, AuditItemType.RUNBOOK, id,
				"Was: " + before + ". Now: " + describe(runbook));
		return RunbookResponse.from(runbook);
	}

	@Transactional
	public void delete(Long id, Long actorId) {
		Runbook runbook = requireRunbook(id);
		String description = describe(runbook);
		runbookRepository.delete(runbook);
		auditService.record(actor(actorId), AuditAction.RUNBOOK_DELETED, AuditItemType.RUNBOOK, id, description);
	}

	// Filtered by org (D48), so another org's service or runbook is "not found" too.
	private ServiceEntity requireService(Long serviceId) {
		return serviceRepository.findById(serviceId)
				.orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));
	}

	private Runbook requireRunbook(Long id) {
		return runbookRepository.findWithStepsById(id)
				.orElseThrow(() -> new NotFoundException("Runbook " + id + " not found"));
	}

	private AppUser actor(Long actorId) {
		return actorId != null ? appUserRepository.findById(actorId).orElse(null) : null;
	}

	private static List<String> stripped(List<String> steps) {
		return steps.stream().map(String::strip).toList();
	}

	private static String describe(Runbook runbook) {
		return "\"%s\" for %s, %d step%s".formatted(runbook.getTitle(), runbook.getService().getName(),
				runbook.getSteps().size(), runbook.getSteps().size() == 1 ? "" : "s");
	}
}
