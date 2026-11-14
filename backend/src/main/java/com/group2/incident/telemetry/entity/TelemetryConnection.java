package com.group2.incident.telemetry.entity;

import com.group2.incident.auth.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * An org's own Grafana (D49): where its Prometheus and Loki are, the read-only token (encrypted), and how its
 * telemetry is named. One per org; telemetry is never read from anywhere else.
 */
@Entity
@Table(name = "telemetry_connection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TelemetryConnection {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@Column(name = "prometheus_url", length = 500)
	private String prometheusUrl;

	@Column(name = "prometheus_user", length = 100)
	private String prometheusUser;

	@Column(name = "loki_url", length = 500)
	private String lokiUrl;

	@Column(name = "loki_user", length = 100)
	private String lokiUser;

	@Column(name = "token_ciphertext", nullable = false, columnDefinition = "text")
	private String tokenCiphertext;

	@Column(name = "service_label", nullable = false, length = 100)
	private String serviceLabel;

	@Column(name = "requests_metric", nullable = false, length = 200)
	private String requestsMetric;

	@Column(name = "status_label", nullable = false, length = 100)
	private String statusLabel;

	@Column(name = "duration_metric", nullable = false, length = 200)
	private String durationMetric;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "updated_by", nullable = false)
	private AppUser updatedBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(nullable = false)
	private long version;

	/** Where the org's stores are and how its telemetry is named; all checked before they get here. */
	public record Settings(
			String prometheusUrl,
			String prometheusUser,
			String lokiUrl,
			String lokiUser,
			String serviceLabel,
			String requestsMetric,
			String statusLabel,
			String durationMetric
	) {
	}

	public TelemetryConnection(Settings settings, String tokenCiphertext, AppUser by, Instant at) {
		this.tokenCiphertext = tokenCiphertext;
		apply(settings, by, at);
	}

	/** Changes the settings; a null ciphertext keeps the token already saved. */
	public void change(Settings settings, String tokenCiphertext, AppUser by, Instant at) {
		if (tokenCiphertext != null) {
			this.tokenCiphertext = tokenCiphertext;
		}
		apply(settings, by, at);
	}

	private void apply(Settings settings, AppUser by, Instant at) {
		this.prometheusUrl = settings.prometheusUrl();
		this.prometheusUser = settings.prometheusUser();
		this.lokiUrl = settings.lokiUrl();
		this.lokiUser = settings.lokiUser();
		this.serviceLabel = settings.serviceLabel();
		this.requestsMetric = settings.requestsMetric();
		this.statusLabel = settings.statusLabel();
		this.durationMetric = settings.durationMetric();
		this.updatedBy = by;
		this.updatedAt = at;
	}
}
