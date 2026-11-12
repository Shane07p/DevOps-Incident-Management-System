package com.group2.incident.common.error;

/**
 * Thrown when an external dependency (such as Grafana Cloud Loki or Prometheus) is slow or unreachable (FR-26.3, FR-27.3).
 */
public class ServiceUnavailableException extends RuntimeException {

	public ServiceUnavailableException(String message) {
		super(message);
	}

	public ServiceUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
