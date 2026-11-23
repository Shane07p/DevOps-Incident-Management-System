package com.settle.alert.service;

import com.settle.incident.entity.Incident;
import com.settle.incident.entity.IncidentStatus;
import com.settle.incident.repository.IncidentRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlertCorrelationService {

	private static final List<IncidentStatus> TERMINAL_STATUSES = List.of(
			IncidentStatus.RESOLVED,
			IncidentStatus.CLOSED
	);

	private final IncidentRepository incidentRepository;

	public Optional<Incident> findMatchingIncident(Long serviceId, String source, String correlationKey) {
		if (correlationKey == null || correlationKey.isBlank()) {
			return Optional.empty();
		}
		return incidentRepository.findActiveByCorrelationForUpdate(
						serviceId,
						source,
						correlationKey,
						TERMINAL_STATUSES
				)
				.stream()
				.findFirst();
	}
}
