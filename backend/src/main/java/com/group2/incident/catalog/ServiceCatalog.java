package com.group2.incident.catalog;

import com.group2.incident.audit.AuditService;
import com.group2.incident.auth.AppUser;
import com.group2.incident.auth.AppUserRepository;
import com.group2.incident.common.ConflictException;
import com.group2.incident.common.FieldValidationException;
import com.group2.incident.common.NotFoundException;
import com.group2.incident.incident.Severity;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds, changes and reads services (US-05). Every change writes an audit entry in the same transaction. */
@Service
@Transactional(readOnly = true)
public class ServiceCatalog {

	static final String ITEM_TYPE = "SERVICE";

	private final AppServiceRepository services;

	private final AppUserRepository users;

	private final AuditService audit;

	private final Clock clock;

	ServiceCatalog(AppServiceRepository services, AppUserRepository users, AuditService audit, Clock clock) {
		this.services = services;
		this.users = users;
		this.audit = audit;
		this.clock = clock;
	}

	public List<ServiceResponse> list() {
		return services.findAllByOrderByNameAsc().stream().map(ServiceResponse::from).toList();
	}

	public ServiceResponse get(Long id) {
		return ServiceResponse.from(load(id));
	}

	/** The response targets new services start with; the form prefills these. */
	public Map<Severity, SlaTargetDto> defaultTargets() {
		Map<Severity, SlaTargetDto> defaults = new EnumMap<>(Severity.class);
		for (Severity severity : Severity.values()) {
			defaults.put(severity, new SlaTargetDto(SlaTargetRules.DEFAULT_RESPONSE_MINUTES.get(severity), null));
		}
		return defaults;
	}

	/**
	 * The service an incident is being reported against, for copying its targets (US-03). Throws a
	 * field error on {@code serviceId} so the incident form can show it.
	 */
	public AppService requireForIncident(Long serviceId) {
		if (serviceId == null) {
			throw new FieldValidationException("serviceId", "Service is required");
		}
		return services.findWithDetailsById(serviceId)
				.orElseThrow(() -> new FieldValidationException("serviceId", "Service not found"));
	}

	@Transactional
	public ServiceResponse create(ServiceRequest request, AppUser actor) {
		Map<Severity, SlaTarget> targets = SlaTargetRules.resolve(request.targets());
		AppUser owner = owner(request.ownerId());
		String name = request.name().strip();
		if (services.existsByNameIgnoreCase(name)) {
			throw duplicate(name);
		}
		AppService service = save(new AppService(name, clean(request.description()), owner,
				request.criticality(), targets, clock.instant()), name);
		audit.record(actor.getId(), "SERVICE_CREATED", ITEM_TYPE, service.getId(), describe(service));
		return ServiceResponse.from(service);
	}

	@Transactional
	public ServiceResponse update(Long id, ServiceRequest request, AppUser actor) {
		AppService service = load(id);
		Map<Severity, SlaTarget> targets = SlaTargetRules.resolve(request.targets());
		// Keeping an owner who has since been disabled is allowed; choosing one is not.
		AppUser owner = service.getOwner().getId().equals(request.ownerId()) ? service.getOwner() : owner(request.ownerId());
		String name = request.name().strip();
		if (services.existsByNameIgnoreCaseAndIdNot(name, id)) {
			throw duplicate(name);
		}
		String description = clean(request.description());
		List<String> changes = changes(service, name, description, owner, request.criticality(), targets);
		if (changes.isEmpty()) {
			return ServiceResponse.from(service);
		}
		service.update(name, description, owner, request.criticality(), targets, clock.instant());
		save(service, name);
		audit.record(actor.getId(), "SERVICE_UPDATED", ITEM_TYPE, id, String.join("; ", changes));
		return ServiceResponse.from(service);
	}

	private AppService load(Long id) {
		return services.findWithDetailsById(id).orElseThrow(() -> new NotFoundException("Service " + id + " not found"));
	}

	private AppUser owner(Long ownerId) {
		return users.findById(ownerId)
				.filter(AppUser::isEnabled)
				.orElseThrow(() -> new FieldValidationException("ownerId", "Owner not found"));
	}

	// The exists check gives the friendly message; the unique index catches two admins saving at once.
	private AppService save(AppService service, String name) {
		try {
			return services.saveAndFlush(service);
		}
		catch (DataIntegrityViolationException ex) {
			throw duplicate(name);
		}
	}

	private static ConflictException duplicate(String name) {
		return new ConflictException("A service named " + name + " already exists");
	}

	private static String clean(String description) {
		return description == null || description.isBlank() ? null : description.strip();
	}

	private static String describe(AppService service) {
		StringBuilder details = new StringBuilder()
				.append(service.getName()).append(" (").append(service.getCriticality())
				.append("), owner ").append(service.getOwner().getDisplayName()).append("; targets");
		for (Severity severity : Severity.values()) {
			SlaTarget target = service.targetFor(severity);
			details.append(' ').append(severity).append(' ')
					.append(target.responseMinutes()).append('/').append(target.resolutionMinutes()).append(" min");
		}
		return details.toString();
	}

	/** Before → after for each field that changes, for the audit entry. */
	private static List<String> changes(AppService before, String name, String description, AppUser owner,
			Criticality criticality, Map<Severity, SlaTarget> targets) {
		List<String> changes = new ArrayList<>();
		if (!before.getName().equals(name)) {
			changes.add("name: " + before.getName() + " → " + name);
		}
		if (!Objects.equals(before.getDescription(), description)) {
			changes.add("description changed");
		}
		if (!before.getOwner().getId().equals(owner.getId())) {
			changes.add("owner: " + before.getOwner().getDisplayName() + " → " + owner.getDisplayName());
		}
		if (before.getCriticality() != criticality) {
			changes.add("criticality: " + before.getCriticality() + " → " + criticality);
		}
		for (Severity severity : Severity.values()) {
			SlaTarget old = before.targetFor(severity);
			SlaTarget now = targets.get(severity);
			if (old.responseMinutes() != now.responseMinutes()) {
				changes.add(severity + " response: " + old.responseMinutes() + " → " + now.responseMinutes() + " min");
			}
			if (old.resolutionMinutes() != now.resolutionMinutes()) {
				changes.add(severity + " resolution: " + old.resolutionMinutes() + " → " + now.resolutionMinutes() + " min");
			}
		}
		return changes;
	}

}
