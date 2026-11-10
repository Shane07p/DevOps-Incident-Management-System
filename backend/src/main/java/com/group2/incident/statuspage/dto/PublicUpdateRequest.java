package com.group2.incident.statuspage.dto;

import com.group2.incident.statuspage.entity.PublicUpdateStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PublicUpdateRequest(
		@NotNull(message = "Status is required")
		PublicUpdateStatus status,

		@NotBlank(message = "Message is required")
		@Size(max = 2000, message = "Message must be at most 2000 characters")
		String message
) {
}
