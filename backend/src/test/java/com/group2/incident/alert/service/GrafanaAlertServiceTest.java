package com.group2.incident.alert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.dto.GrafanaWebhookRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Mapping Grafana's own alert to our alert format (#260). Intake itself is tested in GrafanaWebhookTest. */
class GrafanaAlertServiceTest {

	private static final Instant STARTS = Instant.parse("2026-11-13T10:00:00Z");

	@Test
	void aFiringAlertBecomesOneRequestThatRepeatsIdenticallyWhileItKeepsFiring() {
		GrafanaWebhookRequest.Alert alert = new GrafanaWebhookRequest.Alert("firing",
				Map.of("alertname", "Checkout errors", "service", "checkout", "severity", "critical"),
				Map.of("summary", "Checkout 5xx above 5%", "description", "More than 5% of checkout requests failed"),
				STARTS, "a1b2c3d4e5f60718", "https://grafana.example/alerting/grafana/abc/view");

		AlertRequest request = GrafanaAlertService.toRequest(alert);

		assertThat(request.source()).isEqualTo("grafana");
		assertThat(request.eventId()).isEqualTo("gf-a1b2c3d4e5f60718-" + STARTS.getEpochSecond());
		assertThat(request.service()).isEqualTo("checkout");
		assertThat(request.title()).isEqualTo("Checkout 5xx above 5%");
		assertThat(request.description()).isEqualTo("More than 5% of checkout requests failed");
		assertThat(request.severity()).isEqualTo("SEV1");
		assertThat(request.correlationKey()).isEqualTo("grafana-Checkout errors-checkout");
		assertThat(request.details()).containsOnlyKeys("rule", "fingerprint", "generatorURL");
		// The same alert sent again builds exactly the same request, so intake counts it once.
		assertThat(GrafanaAlertService.toRequest(alert)).isEqualTo(request);
	}

	@Test
	void theNextOutageOfTheSameRuleIsANewEvent() {
		GrafanaWebhookRequest.Alert first = alert(STARTS);
		GrafanaWebhookRequest.Alert next = alert(STARTS.plusSeconds(3600));

		assertThat(GrafanaAlertService.toRequest(next).eventId()).isNotEqualTo(GrafanaAlertService.toRequest(first).eventId());
		assertThat(GrafanaAlertService.toRequest(next).correlationKey())
				.isEqualTo(GrafanaAlertService.toRequest(first).correlationKey());
	}

	@ParameterizedTest
	@CsvSource(nullValues = "-", value = {
			"-, critical, SEV1", "-, warning, SEV2", "-, info, SEV4", "-, page, SEV3", "-, -, SEV3",
			"sev2, critical, SEV2", "SEV9, critical, SEV1" })
	void severityComesFromSettleSeverityOrGrafanasSeverity(String settleSeverity, String severity, String expected) {
		Map<String, String> labels = new java.util.HashMap<>();
		if (settleSeverity != null) {
			labels.put("settle_severity", settleSeverity);
		}
		if (severity != null) {
			labels.put("severity", severity);
		}

		assertThat(GrafanaAlertService.severity(labels)).isEqualTo(expected);
	}

	@Test
	void settleServiceWinsOverServiceAndMissingAnnotationsFallBackToTheRuleName() {
		GrafanaWebhookRequest.Alert alert = new GrafanaWebhookRequest.Alert("firing",
				Map.of("alertname", "Disk full", "service", "node-exporter", "settle_service", "Payments"),
				Map.of(), STARTS, "ff00", null);

		AlertRequest request = GrafanaAlertService.toRequest(alert);

		assertThat(request.service()).isEqualTo("Payments");
		assertThat(request.title()).isEqualTo("Disk full");
		assertThat(request.description()).isEqualTo("Disk full is firing");
		assertThat(request.details()).doesNotContainKey("generatorURL");
	}

	private static GrafanaWebhookRequest.Alert alert(Instant startsAt) {
		return new GrafanaWebhookRequest.Alert("firing", Map.of("alertname", "Down", "service", "search"),
				Map.of("summary", "Search is down"), startsAt, "0a0b0c", null);
	}
}
