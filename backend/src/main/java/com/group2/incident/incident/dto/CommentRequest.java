package com.group2.incident.incident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentRequest(
		@NotBlank(message = "Comment is required")
		@Size(max = 5000, message = "Comment must not exceed 5000 characters")
		String comment
) {
}
