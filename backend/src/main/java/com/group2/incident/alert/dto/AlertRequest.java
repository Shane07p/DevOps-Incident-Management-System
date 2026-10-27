package com.group2.incident.alert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record AlertRequest(
		@NotBlank(message = "source is required")
		@Size(max = 100, message = "source must not exceed 100 characters")
		String source,

		@NotBlank(message = "eventId is required")
		@Size(max = 200, message = "eventId must not exceed 200 characters")
		String eventId,

		@NotBlank(message = "service is required")
		@Size(max = 100, message = "service must not exceed 100 characters")
		String service,

		@NotBlank(message = "title is required")
		@Size(max = 200, message = "title must not exceed 200 characters")
		String title,

		@NotBlank(message = "description is required")
		String description,

		@NotBlank(message = "severity is required")
		@Pattern(regexp = "^(SEV1|SEV2|SEV3|SEV4)$", message = "severity must be one of: SEV1, SEV2, SEV3, SEV4")
		String severity,

		@Size(max = 200, message = "correlationKey must not exceed 200 characters")
		String correlationKey,

		Map<String, Object> details
) {}
