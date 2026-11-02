package com.group2.incident.incident.dto;

import jakarta.validation.constraints.NotBlank;

public record CommentRequest(
		@NotBlank(message = "Comment is required")
		String comment
) {
}
