
package com.group2.incident.incident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EvidenceRequest(
        @NotBlank(message = "URL is required")
        @Size(max = 2048, message = "URL must not exceed 2048 characters")
        String url,

        @NotBlank(message = "Label is required")
        @Size(max = 200, message = "Label must not exceed 200 characters")
        String label
) {
}
