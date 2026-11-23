package com.settle.investigation.dto;

import jakarta.validation.constraints.NotNull;

public record CreateDraftRequest(
		@NotNull(message = "kind is required")
		DraftKind kind
) {
}
