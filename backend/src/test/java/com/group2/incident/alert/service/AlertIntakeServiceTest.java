package com.group2.incident.alert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.alert.dto.AlertAcceptedResponse;
import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.common.error.UnauthorizedException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AlertIntakeServiceTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-10-20T10:00:00Z");

	@Mock
	private ToolService toolService;

	@Mock
	private AlertService alertService;

	private AlertIntakeService alertIntakeService;

	@BeforeEach
	void setUp() {
		alertIntakeService = new AlertIntakeService(toolService, alertService);
	}

	@Test
	void processAlert_validToken_returnsAcceptedResponse() {
		MonitoringTool tool = new MonitoringTool("Prometheus", "hash", FIXED_NOW);
		ReflectionTestUtils.setField(tool, "id", 1L);
		when(toolService.validateToken("valid_token")).thenReturn(tool);

		AlertRequest request = new AlertRequest(
				"prometheus",
				"evt-100",
				"Payments",
				"Latency alert",
				"P99 latency > 500ms",
				"SEV2",
				"latency-key",
				Map.of("p99", "620ms")
		);

		assertThat(alertIntakeService.authenticate("Bearer valid_token")).isSameAs(tool);
		AlertAcceptedResponse expectedResponse = AlertAcceptedResponse.of(request.eventId(), request.source());
		when(alertService.processAlert(tool, request)).thenReturn(expectedResponse);
		AlertAcceptedResponse response = alertIntakeService.processAlert(tool, request);

		assertThat(response).isEqualTo(expectedResponse);
		verify(alertService).processAlert(tool, request);
	}

	@Test
	void authenticate_missingHeader_throwsUnauthorizedException() {
		assertThatThrownBy(() -> alertIntakeService.authenticate(null))
				.isInstanceOf(UnauthorizedException.class)
				.hasMessage("Missing or malformed Authorization header");
	}

	@Test
	void authenticate_nonBearerHeader_throwsUnauthorizedException() {
		assertThatThrownBy(() -> alertIntakeService.authenticate("Basic xyz"))
				.isInstanceOf(UnauthorizedException.class)
				.hasMessage("Missing or malformed Authorization header");
	}

	@Test
	void authenticate_emptyBearerToken_throwsUnauthorizedException() {
		assertThatThrownBy(() -> alertIntakeService.authenticate("Bearer   "))
				.isInstanceOf(UnauthorizedException.class)
				.hasMessage("Missing or malformed Authorization header");
	}

	@Test
	void tokenNeverAppearsInLogsDuringAlertIntake() {
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AlertIntakeService.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> listAppender = new ch.qos.logback.core.read.ListAppender<>();
		listAppender.start();
		logger.addAppender(listAppender);

		String rawToken = "tt_super_secret_token_value_987654321";
		MonitoringTool tool = new MonitoringTool("Prometheus EU", "hash", FIXED_NOW);
		ReflectionTestUtils.setField(tool, "id", 1L);
		when(toolService.validateToken(rawToken)).thenReturn(tool);

		try {
			AlertRequest request = new AlertRequest("prometheus", "evt-1", "Payments", "Title", "Desc", "SEV1", null, null);
			when(alertService.processAlert(tool, request))
					.thenReturn(AlertAcceptedResponse.of(request.eventId(), request.source()));
			alertIntakeService.processAlert(alertIntakeService.authenticate("Bearer " + rawToken), request);

			for (ch.qos.logback.classic.spi.ILoggingEvent event : listAppender.list) {
				assertThat(event.getFormattedMessage()).doesNotContain(rawToken);
				if (event.getArgumentArray() != null) {
					for (Object arg : event.getArgumentArray()) {
						if (arg != null) {
							assertThat(arg.toString()).doesNotContain(rawToken);
						}
					}
				}
			}
		}
		finally {
			logger.detachAppender(listAppender);
			listAppender.stop();
		}
	}
}
