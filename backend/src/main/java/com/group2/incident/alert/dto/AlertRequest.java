package com.group2.incident.alert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

public record AlertRequest(
		@NotBlank(message = "source is required")
		String source,

		@NotBlank(message = "eventId is required")
		String eventId,

		@NotBlank(message = "service is required")
		String service,

		@NotBlank(message = "title is required")
		String title,

		@NotBlank(message = "description is required")
		String description,

		@NotBlank(message = "severity is required")
		@Pattern(regexp = "^(SEV1|SEV2|SEV3|SEV4)$", message = "severity must be one of: SEV1, SEV2, SEV3, SEV4")
		String severity,

		String correlationKey,

		Map<String, Object> details
) {}
