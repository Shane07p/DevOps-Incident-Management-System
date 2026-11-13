package com.group2.incident.alert.dto;

import java.util.List;

/**
 * What happened to each alert in a Grafana notification: the firing ones that reached intake, and the rest
 * with the reason they were left out (resolved, no service label, unknown service, and so on).
 */
public record GrafanaWebhookResponse(
		List<AlertAcceptedResponse> accepted,
		List<Skipped> skipped
) {

	public record Skipped(String fingerprint, String reason) {
	}
}
