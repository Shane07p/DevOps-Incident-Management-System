package com.group2.incident.followup.dto;

import com.group2.incident.followup.entity.FollowUpKind;
import com.group2.incident.followup.entity.FollowUpStatus;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Every field is optional: a field left out (or null) stays as it is. */
public record UpdateFollowUpRequest(
		@Size(max = 200, message = "Title must be at most 200 characters")
		@Pattern(regexp = "(?s).*\\S.*", message = "Title cannot be blank")
		String title,

		FollowUpKind kind,

		Long ownerId,

		LocalDate dueDate,

		FollowUpStatus status
) {
}
