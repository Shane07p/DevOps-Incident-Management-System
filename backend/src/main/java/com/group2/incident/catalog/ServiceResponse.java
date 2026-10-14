package com.group2.incident.catalog;

import com.group2.incident.incident.Severity;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

public record ServiceResponse(
		Long id,
		String name,
		String description,
		Owner owner,
		Criticality criticality,
		Map<Severity, SlaTargetDto> targets,
		Instant createdAt,
		Instant updatedAt) {

	public record Owner(Long id, String displayName) {
	}

	static ServiceResponse from(AppService service) {
		// EnumMap keeps SEV1 to SEV4 in order whatever map Hibernate loaded.
		Map<Severity, SlaTargetDto> targets = new EnumMap<>(Severity.class);
		service.getTargets().forEach((severity, target) -> targets.put(severity, SlaTargetDto.from(target)));
		return new ServiceResponse(service.getId(), service.getName(), service.getDescription(),
				new Owner(service.getOwner().getId(), service.getOwner().getDisplayName()),
				service.getCriticality(), targets, service.getCreatedAt(), service.getUpdatedAt());
	}

}
