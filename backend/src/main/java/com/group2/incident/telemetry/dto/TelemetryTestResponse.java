package com.group2.incident.telemetry.dto;

/**
 * The result of trying the saved connection, per store.
 *
 * @param prometheus "ok", "not connected", or why the call failed
 * @param loki "ok", "not connected", or why the call failed
 */
public record TelemetryTestResponse(String prometheus, String loki) {
}
