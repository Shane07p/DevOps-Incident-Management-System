package com.group2.incident.alert.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The body Grafana Alerting's webhook contact point sends by default, with only the fields we read. Unknown
 * fields (receiver, groupLabels, values, and so on) are ignored.
 */
public record GrafanaWebhookRequest(
		String status,
		List<Alert> alerts
) {

	/** One alert in the notification. {@code fingerprint} stays the same each time this alert fires. */
	public record Alert(
			String status,
			Map<String, String> labels,
			Map<String, String> annotations,
			Instant startsAt,
			String fingerprint,
			String generatorURL
	) {
	}
}
