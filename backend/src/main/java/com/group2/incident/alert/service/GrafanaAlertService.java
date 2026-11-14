package com.group2.incident.alert.service;

import com.group2.incident.alert.dto.AlertAcceptedResponse;
import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.dto.GrafanaWebhookRequest;
import com.group2.incident.alert.dto.GrafanaWebhookResponse;
import com.group2.incident.alert.dto.GrafanaWebhookResponse.Skipped;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.FieldValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Grafana's own webhook format into intake (#260), so an org connects its Grafana alerts without writing a
 * payload template. Each firing alert becomes one {@link AlertRequest} and goes through the same intake as
 * {@code /webhooks/alerts}: the same duplicate guard and the same correlation.
 *
 * <ul>
 * <li>service: the {@code settle_service} label, else {@code service};</li>
 * <li>severity: {@code settle_severity} (SEV1 to SEV4), else {@code severity}: critical is SEV1, warning SEV2,
 * info SEV4, anything else SEV3;</li>
 * <li>eventId: the fingerprint plus the time this firing started, so repeats of one outage are counted once
 * and the next outage is new;</li>
 * <li>correlationKey: the rule name and the service, so alerts of one rule on one service join one incident.</li>
 * </ul>
 * Nothing that changes between repeats (values, the current time) goes into the request, because intake
 * refuses a known eventId that comes back with a different body.
 */
@Service
@RequiredArgsConstructor
public class GrafanaAlertService {

	static final String SOURCE = "grafana";

	/** More than this in one notification is unusual; the rest are skipped rather than processed. */
	static final int MAX_ALERTS = 100;

	private final AlertIntakeService alertIntakeService;

	private final Validator validator;

	public GrafanaWebhookResponse receive(MonitoringTool tool, GrafanaWebhookRequest notification) {
		List<AlertAcceptedResponse> accepted = new ArrayList<>();
		List<Skipped> skipped = new ArrayList<>();
		List<GrafanaWebhookRequest.Alert> alerts = notification.alerts() != null ? notification.alerts() : List.of();
		for (int i = 0; i < alerts.size(); i++) {
			GrafanaWebhookRequest.Alert alert = alerts.get(i);
			String fingerprint = alert != null ? alert.fingerprint() : null;
			if (i >= MAX_ALERTS) {
				skipped.add(new Skipped(fingerprint, "more than " + MAX_ALERTS + " alerts in one notification"));
				continue;
			}
			String reason = reasonToSkip(alert);
			if (reason != null) {
				skipped.add(new Skipped(fingerprint, reason));
				continue;
			}
			AlertRequest request = toRequest(alert);
			Set<ConstraintViolation<AlertRequest>> violations = validator.validate(request);
			if (!violations.isEmpty()) {
				ConstraintViolation<AlertRequest> first = violations.iterator().next();
				skipped.add(new Skipped(fingerprint, first.getPropertyPath() + " " + first.getMessage()));
				continue;
			}
			try {
				accepted.add(alertIntakeService.processAlert(tool, request));
			}
			catch (FieldValidationException e) {
				skipped.add(new Skipped(fingerprint, e.getMessage() + ": " + request.service()));
			}
			catch (ConflictException e) {
				skipped.add(new Skipped(fingerprint, e.getMessage()));
			}
		}
		return new GrafanaWebhookResponse(accepted, skipped);
	}

	private static String reasonToSkip(GrafanaWebhookRequest.Alert alert) {
		if (alert == null) {
			return "empty alert";
		}
		if (!"firing".equalsIgnoreCase(alert.status())) {
			return "not firing";
		}
		if (blank(alert.fingerprint()) || alert.startsAt() == null) {
			return "no fingerprint or start time";
		}
		if (blank(serviceOf(alert))) {
			return "no settle_service or service label";
		}
		return null;
	}

	static AlertRequest toRequest(GrafanaWebhookRequest.Alert alert) {
		Map<String, String> labels = alert.labels() != null ? alert.labels() : Map.of();
		Map<String, String> annotations = alert.annotations() != null ? alert.annotations() : Map.of();
		String service = serviceOf(alert).strip();
		String rule = firstNonBlank(labels.get("alertname"), "Grafana alert");
		String summary = annotations.get("summary");
		String title = truncate(firstNonBlank(summary, rule), 200);
		String description = firstNonBlank(annotations.get("description"), summary, rule + " is firing");

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("rule", rule);
		details.put("fingerprint", alert.fingerprint());
		if (!blank(alert.generatorURL())) {
			details.put("generatorURL", alert.generatorURL());
		}
		return new AlertRequest(
				SOURCE,
				"gf-" + alert.fingerprint() + "-" + alert.startsAt().getEpochSecond(),
				service,
				title,
				description,
				severity(labels),
				truncate("grafana-" + rule + "-" + service, 200),
				details);
	}

	static String severity(Map<String, String> labels) {
		String explicit = labels.get("settle_severity");
		if (explicit != null && explicit.strip().toUpperCase(Locale.ROOT).matches("SEV[1-4]")) {
			return explicit.strip().toUpperCase(Locale.ROOT);
		}
		String severity = labels.get("severity");
		return switch (severity == null ? "" : severity.strip().toLowerCase(Locale.ROOT)) {
			case "critical" -> "SEV1";
			case "warning" -> "SEV2";
			case "info" -> "SEV4";
			default -> "SEV3";
		};
	}

	private static String serviceOf(GrafanaWebhookRequest.Alert alert) {
		Map<String, String> labels = alert.labels() != null ? alert.labels() : Map.of();
		return firstNonBlank(labels.get("settle_service"), labels.get("service"));
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (!blank(value)) {
				return value.strip();
			}
		}
		return null;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String truncate(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max);
	}
}
