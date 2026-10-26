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

	@Mock
	private ToolService toolService;

	private AlertIntakeService alertIntakeService;

	@BeforeEach
	void setUp() {
		alertIntakeService = new AlertIntakeService(toolService);
	}

	@Test
	void processAlert_validToken_returnsAcceptedResponse() {
		MonitoringTool tool = new MonitoringTool("Prometheus", "hash", Instant.now());
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

		AlertAcceptedResponse response = alertIntakeService.processAlert("Bearer valid_token", request);

		assertThat(response.status()).isEqualTo("ACCEPTED");
		assertThat(response.eventId()).isEqualTo("evt-100");
		assertThat(response.source()).isEqualTo("prometheus");
		assertThat(response.message()).isEqualTo("Alert accepted for processing");
	}

	@Test
	void processAlert_missingHeader_throwsUnauthorizedException() {
		AlertRequest request = new AlertRequest("source", "e1", "srv", "title", "desc", "SEV1", null, null);

		assertThatThrownBy(() -> alertIntakeService.processAlert(null, request))
				.isInstanceOf(UnauthorizedException.class)
				.hasMessage("Missing or malformed Authorization header");
	}

	@Test
	void processAlert_nonBearerHeader_throwsUnauthorizedException() {
		AlertRequest request = new AlertRequest("source", "e1", "srv", "title", "desc", "SEV1", null, null);

		assertThatThrownBy(() -> alertIntakeService.processAlert("Basic xyz", request))
				.isInstanceOf(UnauthorizedException.class)
				.hasMessage("Missing or malformed Authorization header");
	}

	@Test
	void processAlert_emptyBearerToken_throwsUnauthorizedException() {
		AlertRequest request = new AlertRequest("source", "e1", "srv", "title", "desc", "SEV1", null, null);

		assertThatThrownBy(() -> alertIntakeService.processAlert("Bearer   ", request))
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
		MonitoringTool tool = new MonitoringTool("Prometheus EU", "hash", Instant.now());
		ReflectionTestUtils.setField(tool, "id", 1L);
		when(toolService.validateToken(rawToken)).thenReturn(tool);

		try {
			AlertRequest request = new AlertRequest("prometheus", "evt-1", "Payments", "Title", "Desc", "SEV1", null, null);
			alertIntakeService.processAlert("Bearer " + rawToken, request);

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
