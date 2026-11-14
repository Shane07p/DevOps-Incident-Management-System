package com.group2.incident.telemetry.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.telemetry.dto.TelemetryConnectionRequest;
import com.group2.incident.telemetry.dto.TelemetryConnectionResponse;
import com.group2.incident.telemetry.dto.TelemetryTestResponse;
import com.group2.incident.telemetry.entity.TelemetryConnection;
import com.group2.incident.telemetry.repository.TelemetryConnectionRepository;
import com.group2.incident.telemetry.service.ActiveConnection.Naming;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each org's own Grafana connection (D49, #260): an Admin saves where its Prometheus and Loki are and a
 * read-only token; telemetry reads only through it. There is no server-wide fallback, so an org with no
 * connection gets 503 and never anyone else's data.
 */
@Service
@RequiredArgsConstructor
public class TelemetryConnectionService {

	static final String NOT_CONNECTED = "Telemetry isn't connected for your organization";

	static final String NOT_CONNECTED_STORE = "not connected";

	/** Common Prometheus conventions, used when an org leaves a naming field empty. */
	static final Naming DEFAULT_NAMING = new Naming("service", "http_requests_total", "status",
			"http_request_duration_seconds");

	// Prometheus's own name rules. A value that passes can sit inside a query with no escaping.
	private static final Pattern METRIC_NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");

	private static final Pattern LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

	private final TelemetryConnectionRepository connections;

	private final TokenCipher cipher;

	private final TelemetryHttp http;

	private final AuditService auditService;

	private final AppUserRepository users;

	private final Clock clock;

	@Transactional(readOnly = true)
	public TelemetryConnectionResponse get() {
		return connections.findFirstByOrderByIdAsc()
				.map(TelemetryConnectionService::toResponse)
				.orElseGet(() -> new TelemetryConnectionResponse(false, null, null, null, null, false,
						DEFAULT_NAMING.serviceLabel(), DEFAULT_NAMING.requestsMetric(), DEFAULT_NAMING.statusLabel(),
						DEFAULT_NAMING.durationMetric(), null, null));
	}

	@Transactional
	public TelemetryConnectionResponse save(TelemetryConnectionRequest request, long actorId) {
		String prometheusUrl = blankToNull(request.prometheusUrl());
		String lokiUrl = blankToNull(request.lokiUrl());
		if (prometheusUrl == null && lokiUrl == null) {
			throw new FieldValidationException("prometheusUrl", "Enter the Prometheus URL, the Loki URL, or both");
		}
		if (prometheusUrl != null) {
			http.checkUrl("prometheusUrl", prometheusUrl);
		}
		if (lokiUrl != null) {
			http.checkUrl("lokiUrl", lokiUrl);
		}
		TelemetryConnection.Settings settings = new TelemetryConnection.Settings(
				prometheusUrl == null ? null : prometheusUrl.strip(),
				user("prometheusUser", request.prometheusUser()),
				lokiUrl == null ? null : lokiUrl.strip(),
				user("lokiUser", request.lokiUser()),
				name("serviceLabel", request.serviceLabel(), DEFAULT_NAMING.serviceLabel(), LABEL_NAME, 100),
				name("requestsMetric", request.requestsMetric(), DEFAULT_NAMING.requestsMetric(), METRIC_NAME, 200),
				name("statusLabel", request.statusLabel(), DEFAULT_NAMING.statusLabel(), LABEL_NAME, 100),
				name("durationMetric", request.durationMetric(), DEFAULT_NAMING.durationMetric(), METRIC_NAME, 200));

		AppUser actor = users.findById(actorId)
				.orElseThrow(() -> new NotFoundException("User " + actorId + " not found"));
		String token = blankToNull(request.token());
		String ciphertext = token == null ? null : cipher.encrypt(OrgContext.current(), token.strip());

		TelemetryConnection connection = connections.findFirstByOrderByIdAsc().orElse(null);
		if (connection == null) {
			if (ciphertext == null) {
				throw new FieldValidationException("token", "Enter the read-only token");
			}
			connection = new TelemetryConnection(settings, ciphertext, actor, clock.instant());
		}
		else {
			connection.change(settings, ciphertext, actor, clock.instant());
		}
		TelemetryConnection saved = connections.saveAndFlush(connection);
		auditService.record(actor, AuditAction.TELEMETRY_CONNECTED, AuditItemType.ORG, OrgContext.current(),
				"prometheus: " + nullToDash(saved.getPrometheusUrl()) + ", loki: " + nullToDash(saved.getLokiUrl())
						+ (ciphertext != null ? ", token replaced" : ""));
		return toResponse(saved);
	}

	@Transactional
	public void delete(long actorId) {
		connections.findFirstByOrderByIdAsc().ifPresent(connection -> {
			AppUser actor = users.findById(actorId)
					.orElseThrow(() -> new NotFoundException("User " + actorId + " not found"));
			connections.delete(connection);
			auditService.record(actor, AuditAction.TELEMETRY_DISCONNECTED, AuditItemType.ORG, OrgContext.current(),
					"Grafana connection removed");
		});
	}

	/**
	 * The current org's connection, ready to call; 503 when there is none. Addresses are checked again here.
	 * Not transactional on purpose: the repository reads in its own short transaction, and the DNS lookups
	 * that follow never hold a database connection.
	 */
	public ActiveConnection current() {
		TelemetryConnection connection = connections.findFirstByOrderByIdAsc()
				.orElseThrow(() -> new ServiceUnavailableException(NOT_CONNECTED));
		URI prometheus = recheck("Prometheus", connection.getPrometheusUrl());
		URI loki = recheck("Loki", connection.getLokiUrl());
		String token = cipher.decrypt(connection.getOrgId(), connection.getTokenCiphertext());
		return new ActiveConnection(prometheus, connection.getPrometheusUser(), loki, connection.getLokiUser(), token,
				new Naming(connection.getServiceLabel(), connection.getRequestsMetric(), connection.getStatusLabel(),
						connection.getDurationMetric()));
	}

	/** Tries each saved store with a cheap read. Runs outside any transaction: the calls can take seconds. */
	public TelemetryTestResponse test() {
		ActiveConnection connection = current();
		// A store the org left out is reported, not called: either one may be missing (logs only, metrics only).
		String prometheus = connection.prometheus() == null ? NOT_CONNECTED_STORE
				: probe(connection.prometheus(), prometheusPath(connection.prometheus(), "/query"),
						Map.of("query", "vector(1)"), connection.authorization(connection.prometheusUser()), "Metrics");
		String loki = connection.loki() == null ? NOT_CONNECTED_STORE
				: probe(connection.loki(), "/loki/api/v1/labels", Map.of(),
						connection.authorization(connection.lokiUser()), "Logs");
		return new TelemetryTestResponse(prometheus, loki);
	}

	/** Grafana Cloud's Prometheus address ends in /api/prom and serves /api/v1 under it; a plain one serves it at the root. */
	static String prometheusPath(URI prometheus, String endpoint) {
		String base = prometheus.toString().replaceAll("/+$", "");
		return (base.endsWith("/api/v1") ? "" : "/api/v1") + endpoint;
	}

	private String probe(URI base, String path, Map<String, String> params, String authorization, String store) {
		try {
			http.get(base, path, params, authorization, store);
			return "ok";
		}
		catch (ServiceUnavailableException e) {
			return e.getMessage();
		}
	}

	private URI recheck(String store, String url) {
		if (url == null) {
			return null;
		}
		try {
			return http.checkUrl("url", url);
		}
		catch (FieldValidationException e) {
			throw new ServiceUnavailableException("The saved " + store + " URL is refused now; check it in Settings");
		}
	}

	private static TelemetryConnectionResponse toResponse(TelemetryConnection connection) {
		return new TelemetryConnectionResponse(true, connection.getPrometheusUrl(), connection.getPrometheusUser(),
				connection.getLokiUrl(), connection.getLokiUser(), true, connection.getServiceLabel(),
				connection.getRequestsMetric(), connection.getStatusLabel(), connection.getDurationMetric(),
				connection.getUpdatedAt(), connection.getUpdatedBy().getDisplayName());
	}

	private static String user(String field, String value) {
		String user = blankToNull(value);
		if (user != null && (user.contains(":") || user.chars().anyMatch(Character::isISOControl))) {
			throw new FieldValidationException(field, "must not contain a colon or control characters");
		}
		return user == null ? null : user.strip();
	}

	private static String name(String field, String value, String fallback, Pattern rule, int max) {
		String name = blankToNull(value);
		if (name == null) {
			return fallback;
		}
		name = name.strip();
		if (name.length() > max || !rule.matcher(name).matches()) {
			throw new FieldValidationException(field, "must be a valid Prometheus name: letters, digits, and _"
					+ (rule == METRIC_NAME ? " or :" : "") + ", not starting with a digit");
		}
		return name;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static String nullToDash(String value) {
		return value == null ? "-" : value;
	}
}
