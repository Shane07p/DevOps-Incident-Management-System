package com.group2.incident.incident.service;

import com.group2.incident.common.tenancy.OrgContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.embedding.worker-enabled", havingValue = "true", matchIfMissing = true)
public class IncidentEmbeddingWorker {

	private final IncidentEmbeddingService incidentEmbeddingService;

	public IncidentEmbeddingWorker(IncidentEmbeddingService incidentEmbeddingService) {
		this.incidentEmbeddingService = incidentEmbeddingService;
	}

	@Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
	public void retryPendingEmbeddings() {
		OrgContext.runAsRoot(incidentEmbeddingService::retryPendingEmbeddings);
	}
}
