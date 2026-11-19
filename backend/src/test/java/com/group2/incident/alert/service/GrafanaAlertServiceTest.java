package com.group2.incident.alert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.group2.incident.alert.dto.AlertAcceptedResponse;
import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.dto.GrafanaWebhookRequest;
import com.group2.incident.alert.dto.GrafanaWebhookResponse;
import com.group2.incident.alert.dto.GrafanaWebhookResponse.Skipped;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.FieldValidationException;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

	@Test
	void aNotificationWithoutAlertsTakesNothing() {
		GrafanaWebhookResponse response = receiver(mock(AlertIntakeService.class))
				.receive(mock(MonitoringTool.class), new GrafanaWebhookRequest("firing", null));

		assertThat(response.accepted()).isEmpty();
		assertThat(response.skipped()).isEmpty();
	}

	// Grafana sends the whole group; each alert that can't become an incident alert is listed with the reason.
	@Test
	void eachAlertThatIsNotTakenSaysWhy() {
		AlertIntakeService intake = mock(AlertIntakeService.class);
		MonitoringTool tool = mock(MonitoringTool.class);
		when(intake.processAlert(eq(tool), any())).thenAnswer(inv -> {
			AlertRequest request = inv.getArgument(1);
			return switch (request.service()) {
				case "unknown" -> throw new FieldValidationException("service", "Service not found");
				case "duplicate" -> throw new ConflictException("This alert was already received");
				default -> AlertAcceptedResponse.of(request.eventId(), request.source());
			};
		});
		List<GrafanaWebhookRequest.Alert> alerts = Arrays.asList(
				null,
				new GrafanaWebhookRequest.Alert("resolved", Map.of("service", "search"), null, STARTS, "r1", null),
				new GrafanaWebhookRequest.Alert("firing", Map.of("service", "search"), null, STARTS, " ", null),
				new GrafanaWebhookRequest.Alert("firing", Map.of("service", "search"), null, null, "n1", null),
				new GrafanaWebhookRequest.Alert("firing", null, null, STARTS, "l1", null),
				new GrafanaWebhookRequest.Alert("firing", Map.of("service", "s".repeat(101)), null, STARTS, "v1", null),
				new GrafanaWebhookRequest.Alert("FIRING", Map.of("service", "unknown"), null, STARTS, "u1", null),
				new GrafanaWebhookRequest.Alert("firing", Map.of("service", "duplicate"), null, STARTS, "d1", null),
				new GrafanaWebhookRequest.Alert("firing", Map.of("service", "search"), null, STARTS, "ok1", null));

		GrafanaWebhookResponse response = receiver(intake).receive(tool, new GrafanaWebhookRequest("firing", alerts));

		assertThat(response.skipped()).extracting(Skipped::fingerprint, Skipped::reason).containsExactly(
				tuple(null, "empty alert"),
				tuple("r1", "not firing"),
				tuple(" ", "no fingerprint or start time"),
				tuple("n1", "no fingerprint or start time"),
				tuple("l1", "no settle_service or service label"),
				tuple("v1", "service service must not exceed 100 characters"),
				tuple("u1", "Service not found: unknown"),
				tuple("d1", "This alert was already received"));
		assertThat(response.accepted()).extracting(AlertAcceptedResponse::eventId)
				.containsExactly("gf-ok1-" + STARTS.getEpochSecond());
	}

	@Test
	void alertsPastTheFirstHundredInOneNotificationAreSkipped() {
		AlertIntakeService intake = mock(AlertIntakeService.class);
		when(intake.processAlert(any(), any())).thenAnswer(inv -> {
			AlertRequest request = inv.getArgument(1);
			return AlertAcceptedResponse.of(request.eventId(), request.source());
		});
		List<GrafanaWebhookRequest.Alert> alerts = new ArrayList<>();
		for (int i = 0; i <= GrafanaAlertService.MAX_ALERTS; i++) {
			alerts.add(new GrafanaWebhookRequest.Alert("firing", Map.of("service", "search"), null, STARTS, "fp" + i, null));
		}

		GrafanaWebhookResponse response = receiver(intake).receive(mock(MonitoringTool.class), new GrafanaWebhookRequest("firing", alerts));

		assertThat(response.accepted()).hasSize(GrafanaAlertService.MAX_ALERTS);
		assertThat(response.skipped()).extracting(Skipped::fingerprint, Skipped::reason)
				.containsExactly(tuple("fp100", "more than 100 alerts in one notification"));
	}

	@Test
	void aLongSummaryIsCutToTheTitleLimit() {
		GrafanaWebhookRequest.Alert alert = new GrafanaWebhookRequest.Alert("firing", Map.of("service", "search"),
				Map.of("summary", "x".repeat(250)), STARTS, "ab12", null);

		AlertRequest request = GrafanaAlertService.toRequest(alert);

		assertThat(request.title()).hasSize(200);
		assertThat(request.description()).hasSize(250);
		assertThat(request.correlationKey()).isEqualTo("grafana-Grafana alert-search");
	}

	private static GrafanaAlertService receiver(AlertIntakeService intake) {
		return new GrafanaAlertService(intake, Validation.buildDefaultValidatorFactory().getValidator());
	}

	private static GrafanaWebhookRequest.Alert alert(Instant startsAt) {
		return new GrafanaWebhookRequest.Alert("firing", Map.of("alertname", "Down", "service", "search"),
				Map.of("summary", "Search is down"), startsAt, "0a0b0c", null);
	}
}
