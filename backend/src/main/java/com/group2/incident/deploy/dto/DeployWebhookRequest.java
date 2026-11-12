package com.group2.incident.deploy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record DeployWebhookRequest(
		@NotBlank(message = "service is required")
		@Size(max = 100, message = "service must not exceed 100 characters")
		String service,

		@NotBlank(message = "version is required")
		@Size(max = 100, message = "version must not exceed 100 characters")
		String version,

		@NotBlank(message = "commit is required")
		@Size(max = 100, message = "commit must not exceed 100 characters")
		String commit,

		@NotBlank(message = "author is required")
		@Size(max = 100, message = "author must not exceed 100 characters")
		String author,

		@NotNull(message = "deployedAt is required")
		Instant deployedAt
) {
	public DeployWebhookRequest {
		service = strip(service);
		version = strip(version);
		commit = strip(commit);
		author = strip(author);
	}

	private static String strip(String value) {
		return value == null ? null : value.strip();
	}
}
