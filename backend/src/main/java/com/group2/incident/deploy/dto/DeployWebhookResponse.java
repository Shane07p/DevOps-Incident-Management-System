package com.group2.incident.deploy.dto;

import java.time.Instant;

public record DeployWebhookResponse(
		Long id,
		String service,
		String version,
		String commit,
		String author,
		Instant deployedAt,
		String status,
		String message
) {
	public static DeployWebhookResponse recorded(Long id, String service, String version, String commit, String author, Instant deployedAt) {
		return new DeployWebhookResponse(id, service, version, commit, author, deployedAt, "RECORDED", "Deployment recorded");
	}

	public static DeployWebhookResponse duplicate(Long id, String service, String version, String commit, String author, Instant deployedAt) {
		return new DeployWebhookResponse(id, service, version, commit, author, deployedAt, "DUPLICATE", "Deployment already recorded");
	}
}
