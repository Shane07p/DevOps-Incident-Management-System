package com.group2.incident.incident.dto;

import jakarta.validation.constraints.NotNull;

public record ReassignRequest(
		@NotNull(message = "Assignee is required")
		Long assigneeId
) {
}

