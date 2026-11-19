package com.group2.incident.heartbeat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.entity.Alert;
import com.group2.incident.alert.repository.AlertRepository;
import com.group2.incident.alert.service.AlertService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.heartbeat.entity.Heartbeat;
import com.group2.incident.heartbeat.repository.DueHeartbeat;
import com.group2.incident.heartbeat.repository.HeartbeatRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The worker found a heartbeat late; by the time its own transaction opens, the heartbeat may have changed. Each such
 * change means no alert. The full worker runs against PostgreSQL in {@code HeartbeatTest}.
 */
class HeartbeatRecorderTest {

	private static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");

	private final HeartbeatRepository heartbeats = mock(HeartbeatRepository.class);

	private final AlertService alerts = mock(AlertService.class);

	private final HeartbeatRecorder recorder = new HeartbeatRecorder(heartbeats, alerts, mock(AlertRepository.class),
			mock(TimelineEntryRepository.class));

	private Heartbeat heartbeat;

	@BeforeEach
	void setUp() {
		AppUser owner = new AppUser("owner@test.local", "Owner", "hash", T0);
		ServiceEntity backups = new ServiceEntity("Backups", null, owner, Criticality.MEDIUM, T0);
		ReflectionTestUtils.setField(backups, "id", 1L);
		heartbeat = new Heartbeat(backups, "Nightly backup", 3600, 300, "token", true, T0);
		ReflectionTestUtils.setField(heartbeat, "id", 5L);
		ReflectionTestUtils.setField(heartbeat, "version", 3L);
		when(heartbeats.findWithServiceById(5L)).thenReturn(Optional.of(heartbeat));
	}

	@Test
	void aLateHeartbeatRaisesOneSev2AlertForItsService() {
		Alert alert = mock(Alert.class);
		when(alert.getId()).thenReturn(77L);
		when(alerts.raiseMonitorAlert(any())).thenReturn(alert);
		Instant missed = T0.plusSeconds(4000);

		assertThat(recorder.recordOverdue(due(3L), missed)).isTrue();

		ArgumentCaptor<AlertRequest> raised = ArgumentCaptor.forClass(AlertRequest.class);
		verify(alerts).raiseMonitorAlert(raised.capture());
		assertThat(raised.getValue().eventId()).isEqualTo("heartbeat-5-missed-" + missed.toEpochMilli());
		assertThat(raised.getValue().service()).isEqualTo("Backups");
		assertThat(raised.getValue().severity()).isEqualTo("SEV2");
		assertThat(raised.getValue().correlationKey()).isEqualTo("heartbeat-5");
		assertThat(heartbeat.getActiveAlertId()).isEqualTo(77L);
	}

	@Test
	void aHeartbeatDeletedEditedPausedOrAlreadyAlertingRaisesNothing() {
		when(heartbeats.findWithServiceById(6L)).thenReturn(Optional.empty());
		Instant missed = T0.plusSeconds(4000);

		assertThat(recorder.recordOverdue(new DueHeartbeat(6L, 1L, 0L, T0), missed)).isFalse();
		assertThat(recorder.recordOverdue(due(2L), missed)).isFalse();

		heartbeat.change(heartbeat.getService(), "Nightly backup", 3600, 300, false, T0.plusSeconds(10));
		assertThat(recorder.recordOverdue(due(3L), missed)).isFalse();

		heartbeat.change(heartbeat.getService(), "Nightly backup", 3600, 300, true, T0.plusSeconds(20));
		heartbeat.alertRaised(70L);
		assertThat(recorder.recordOverdue(due(3L), missed)).isFalse();

		verify(alerts, never()).raiseMonitorAlert(any());
	}

	@Test
	void aPingForAnUnknownHeartbeatIsNotFound() {
		when(heartbeats.findWithServiceById(9L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> recorder.recordPing(9L, T0))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("Heartbeat 9 not found");
	}

	private static DueHeartbeat due(long version) {
		return new DueHeartbeat(5L, 1L, version, T0.plusSeconds(3900));
	}
}
