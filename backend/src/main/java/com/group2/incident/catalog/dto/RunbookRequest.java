package com.group2.incident.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** A runbook's title and its steps, in the order they are done. */
public record RunbookRequest(
		@NotBlank(message = "Title is required")
		@Size(max = 200, message = "Title must be at most 200 characters")
		String title,
		@NotEmpty(message = "Add at least one step")
		@Size(max = 50, message = "A runbook has at most 50 steps")
		List<@NotBlank(message = "A step cannot be empty") @Size(max = 2000, message = "A step must be at most 2000 characters") String> steps
) {
}
