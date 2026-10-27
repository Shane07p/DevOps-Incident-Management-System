package com.group2.incident.alert.service;

import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.common.error.FieldValidationException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlertValidationService {

	private static final Set<String> VALID_SEVERITIES = Set.of("SEV1", "SEV2", "SEV3", "SEV4");

	public AlertRequest validate(AlertRequest request) {
		if (request == null) {
			throw new FieldValidationException("alert", "Alert payload is required");
		}
		requireText("source", request.source());
		requireText("eventId", request.eventId());
		requireText("service", request.service());
		requireText("title", request.title());
		requireText("description", request.description());
		requireText("severity", request.severity());
		if (!VALID_SEVERITIES.contains(request.severity().strip())) {
			throw new FieldValidationException("severity", "severity must be one of: SEV1, SEV2, SEV3, SEV4");
		}

		String correlationKey = request.correlationKey() == null || request.correlationKey().isBlank()
				? null
				: request.correlationKey().strip();
		if (correlationKey != null && correlationKey.length() > 200) {
			throw new FieldValidationException("correlationKey", "correlationKey must not exceed 200 characters");
		}
		return new AlertRequest(
				request.source().strip(),
				request.eventId().strip(),
				request.service().strip(),
				request.title(),
				request.description(),
				request.severity().strip(),
				correlationKey,
				request.details()
		);
	}

	private static void requireText(String field, String value) {
		if (value == null || value.isBlank()) {
			throw new FieldValidationException(field, field + " is required");
		}
	}
}
