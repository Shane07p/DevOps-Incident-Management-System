package com.group2.incident.telemetry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.common.error.ApiExceptionHandler;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.telemetry.controller.TelemetryController;
import com.group2.incident.telemetry.dto.LogEntryResponse;
import com.group2.incident.telemetry.dto.MetricDataPoint;
import com.group2.incident.telemetry.dto.ServiceMetricsResponse;
import com.group2.incident.telemetry.service.TelemetryService;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TelemetryControllerTest {

	private TelemetryService telemetryService;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		telemetryService = mock(TelemetryService.class);
		mvc = MockMvcBuilders.standaloneSetup(new TelemetryController(telemetryService))
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
	}

	@Test
	@DisplayName("GET /api/telemetry/logs returns 200 with list of logs")
	void testGetLogsSuccess() throws Exception {
		Instant now = Instant.parse("2026-11-10T12:00:00Z");
		List<LogEntryResponse> sampleLogs = List.of(
				new LogEntryResponse(
						"1731240000000000000",
						now,
						"checkout",
						1L,
						"WARN",
						"Order payment failed",
						Map.of("orderId", "ord_456")
				)
		);

		when(telemetryService.queryLogs(eq(1L), eq("checkout"), eq("WARN"), eq("failed"), any(), any(), eq(50)))
				.thenReturn(sampleLogs);

		mvc.perform(get("/api/telemetry/logs")
						.param("serviceId", "1")
						.param("service", "checkout")
						.param("level", "WARN")
						.param("text", "failed")
						.param("from", "2026-11-10T11:00:00Z")
						.param("to", "2026-11-10T12:00:00Z")
						.param("limit", "50"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value("1731240000000000000"))
				.andExpect(jsonPath("$[0].service").value("checkout"))
				.andExpect(jsonPath("$[0].level").value("WARN"))
				.andExpect(jsonPath("$[0].message").value("Order payment failed"))
				.andExpect(jsonPath("$[0].attributes.orderId").value("ord_456"));
	}

	@Test
	@DisplayName("GET /api/telemetry/logs returns 400 when timestamp format is invalid")
	void testGetLogsInvalidTimestamp() throws Exception {
		mvc.perform(get("/api/telemetry/logs")
						.param("from", "invalid-timestamp"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.from").value("'from' must be an ISO-8601 UTC timestamp (e.g. 2026-10-01T00:00:00Z)"));
	}

	@Test
	@DisplayName("GET /api/telemetry/logs returns 400 when time range exceeds 24 hours")
	void testGetLogsExceeds24Hours() throws Exception {
		when(telemetryService.queryLogs(any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new FieldValidationException("from", "Time range cannot exceed 24 hours"));

		mvc.perform(get("/api/telemetry/logs")
						.param("from", "2026-11-08T00:00:00Z")
						.param("to", "2026-11-10T00:00:00Z"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.from").value("Time range cannot exceed 24 hours"));
	}

	@Test
	@DisplayName("GET /api/telemetry/logs returns 503 when Grafana Loki is unavailable")
	void testGetLogsServiceUnavailable() throws Exception {
		when(telemetryService.queryLogs(any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new ServiceUnavailableException("Log store is slow or unavailable"));

		mvc.perform(get("/api/telemetry/logs")
						.param("service", "checkout"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value("Log store is slow or unavailable"));
	}

	@Test
	@DisplayName("GET /api/telemetry/metrics returns 200 with service metrics")
	void testGetMetricsSuccess() throws Exception {
		Instant now = Instant.parse("2026-11-10T12:00:00Z");
		Instant from = now.minusSeconds(3600);
		ServiceMetricsResponse sample = new ServiceMetricsResponse(
				1L,
				"checkout",
				from,
				now,
				List.of(new MetricDataPoint(now, 10.5)),
				List.of(new MetricDataPoint(now, 0.05)),
				List.of(new MetricDataPoint(now, 0.12)),
				List.of(new MetricDataPoint(now, 0.45))
		);

		when(telemetryService.queryMetrics(eq(1L), eq("checkout"), any(), any(), eq("60s")))
				.thenReturn(sample);

		mvc.perform(get("/api/telemetry/metrics")
						.param("serviceId", "1")
						.param("service", "checkout")
						.param("from", "2026-11-10T11:00:00Z")
						.param("to", "2026-11-10T12:00:00Z")
						.param("step", "60s"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.serviceId").value(1))
				.andExpect(jsonPath("$.service").value("checkout"))
				.andExpect(jsonPath("$.requestRate[0].value").value(10.5))
				.andExpect(jsonPath("$.errorRate[0].value").value(0.05))
				.andExpect(jsonPath("$.latencyP50[0].value").value(0.12))
				.andExpect(jsonPath("$.latencyP95[0].value").value(0.45));
	}

	@Test
	@DisplayName("GET /api/telemetry/metrics returns 503 when Prometheus is unavailable")
	void testGetMetricsServiceUnavailable() throws Exception {
		when(telemetryService.queryMetrics(any(), any(), any(), any(), any()))
				.thenThrow(new ServiceUnavailableException("Metrics store is slow or unavailable"));

		mvc.perform(get("/api/telemetry/metrics")
						.param("service", "checkout"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value("Metrics store is slow or unavailable"));
	}
}
