package com.group2.incident.alert.service;

import com.group2.incident.alert.entity.Alert;
import com.group2.incident.alert.repository.AlertRepository;
import com.group2.incident.common.error.ConflictException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlertDeduplicationService {

	private final AlertRepository alertRepository;

	public Optional<Alert> findExisting(String source, String eventId, String fingerprint) {
		return alertRepository.findBySourceAndEventIdForUpdate(source, eventId)
				.map(existing -> {
					if (!existing.getPayloadFingerprint().equals(fingerprint)) {
						throw new ConflictException("Duplicate eventId with changed payload");
					}
					return existing;
				});
	}
}
