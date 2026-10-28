package com.group2.incident.alert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateToolRequest(
		@NotBlank(message = "Tool name is required")
		@Size(max = 100, message = "Tool name must not exceed 100 characters")
		String name
) {}
