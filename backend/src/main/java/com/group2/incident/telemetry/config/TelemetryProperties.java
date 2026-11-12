package com.group2.incident.telemetry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server settings for reading each org's own Grafana (D49, #260). Where the stores are and the tokens are per
 * org, in the database; only these stay in the environment.
 *
 * @param secretKey base64 AES-256 key that encrypts each org's read token ({@code TELEMETRY_SECRET_KEY})
 * @param allowPrivateTargets lets an org point at private addresses and plain http, for local runs against a
 *        Grafana in Docker; production leaves it off
 * @param timeoutSeconds how long one call to Prometheus or Loki may take, from connecting to the last byte
 */
@ConfigurationProperties(prefix = "app.telemetry")
public record TelemetryProperties(
		@DefaultValue("") String secretKey,
		@DefaultValue("false") boolean allowPrivateTargets,
		@DefaultValue("5") int timeoutSeconds
) {

	public TelemetryProperties {
		if (secretKey == null) {
			secretKey = "";
		}
		if (timeoutSeconds <= 0) {
			timeoutSeconds = 5;
		}
	}
}
