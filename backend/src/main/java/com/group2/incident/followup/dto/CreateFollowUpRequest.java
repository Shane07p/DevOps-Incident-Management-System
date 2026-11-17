package com.group2.incident.followup.dto;

import com.group2.incident.followup.entity.FollowUpKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateFollowUpRequest(
		@NotBlank(message = "Title is required")
		@Size(max = 200, message = "Title must be at most 200 characters")
		String title,

		@NotNull(message = "Kind is required")
		FollowUpKind kind,

		@NotNull(message = "Owner is required")
		Long ownerId,

		@NotNull(message = "Due date is required")
		LocalDate dueDate
) {
}
