package com.group2.incident.investigation.controller;

import com.group2.incident.investigation.dto.CreateDraftRequest;
import com.group2.incident.investigation.dto.DraftResponse;
import com.group2.incident.investigation.service.DraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Drafts", description = "AI-assisted incident summaries, status updates, and postmortem drafts")
@RequiredArgsConstructor
public class DraftController {

	private final DraftService draftService;

	@PostMapping("/{id}/drafts")
	@Operation(summary = "Generate an editable AI draft (SUMMARY, STATUS_UPDATE, or POSTMORTEM) without saving")
	public DraftResponse generateDraft(
			@PathVariable Long id,
			@Valid @RequestBody CreateDraftRequest request
	) {
		return draftService.generateDraft(id, request.kind());
	}
}
