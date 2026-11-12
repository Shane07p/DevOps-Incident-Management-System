package com.group2.incident.telemetry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for Grafana Cloud Prometheus and Loki read-only telemetry queries (D34, Issue #156).
 */
@ConfigurationProperties(prefix = "grafana")
public record TelemetryProperties(
		@DefaultValue PrometheusConfig prometheus,
		@DefaultValue LokiConfig loki,
		@DefaultValue("") String readToken,
		@DefaultValue("5") int timeoutSeconds
) {

	public TelemetryProperties {
		if (prometheus == null) {
			prometheus = new PrometheusConfig("", "");
		}
		if (loki == null) {
			loki = new LokiConfig("", "");
		}
		if (readToken == null) {
			readToken = "";
		}
		if (timeoutSeconds <= 0) {
			timeoutSeconds = 5;
		}
	}

	public record PrometheusConfig(
			@DefaultValue("") String url,
			@DefaultValue("") String user
	) {
		public PrometheusConfig {
			if (url == null) {
				url = "";
			}
			if (user == null) {
				user = "";
			}
		}
	}

	public record LokiConfig(
			@DefaultValue("") String url,
			@DefaultValue("") String user
	) {
		public LokiConfig {
			if (url == null) {
				url = "";
			}
			if (user == null) {
				user = "";
			}
		}
	}

	public String prometheusUrl() {
		return prometheus != null ? prometheus.url() : "";
	}

	public String prometheusUser() {
		return prometheus != null ? prometheus.user() : "";
	}

	public String lokiUrl() {
		return loki != null ? loki.url() : "";
	}

	public String lokiUser() {
		return loki != null ? loki.user() : "";
	}
}
