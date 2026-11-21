package com.group2.incident.heartbeat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** How an Admin's edit and a missed ping move a heartbeat's schedule and outage (FR-22). */
class HeartbeatScheduleTest {

	private static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");

	private final ServiceEntity backups = service(1L, "Backups");

	private final ServiceEntity billing = service(2L, "Billing");

	@Test
	void pointingAHeartbeatAtAnotherServiceStartsItOver() {
		Heartbeat heartbeat = new Heartbeat(backups, "Nightly backup", 3600, 300, "token", true, T0);
		heartbeat.recordPing(T0.plusSeconds(60));
		heartbeat.recordMissed(T0.plusSeconds(4000));
		heartbeat.alertRaised(9L);
		Instant now = T0.plusSeconds(5000);

		heartbeat.change(billing, "Nightly invoices", 1800, 120, true, now);

		assertThat(heartbeat.getService()).isSameAs(billing);
		assertThat(heartbeat.getOutageStartedAt()).isNull();
		assertThat(heartbeat.getActiveAlertId()).isNull();
		assertThat(heartbeat.getLastPingAt()).isNull();
		assertThat(heartbeat.getNextDueAt()).isEqualTo(now.plusSeconds(1800));
		assertThat(heartbeat.getLateAfter()).isEqualTo(now.plusSeconds(1800 + 120));
	}

	@Test
	void aNewIntervalCountsFromNowUntilTheFirstPingThenFromTheLastPing() {
		Heartbeat heartbeat = new Heartbeat(backups, "Nightly backup", 3600, 300, "token", true, T0);
		Instant edit = T0.plusSeconds(100);

		heartbeat.change(service(1L, "Backups"), "Nightly backup", 600, 300, true, edit);

		assertThat(heartbeat.getNextDueAt()).isEqualTo(edit.plusSeconds(600));
		assertThat(heartbeat.getLateAfter()).isEqualTo(edit.plusSeconds(900));

		Instant ping = T0.plusSeconds(200);
		heartbeat.recordPing(ping);
		heartbeat.change(backups, "Nightly backup", 600, 60, true, T0.plusSeconds(300));

		assertThat(heartbeat.getNextDueAt()).isEqualTo(ping.plusSeconds(600));
		assertThat(heartbeat.getLateAfter()).isEqualTo(ping.plusSeconds(660));
	}

	@Test
	void renamingOrPausingKeepsTheSchedule() {
		Heartbeat heartbeat = new Heartbeat(backups, "Nightly backup", 3600, 300, "token", true, T0);
		Instant due = heartbeat.getNextDueAt();

		heartbeat.change(backups, "Backup", 3600, 300, false, T0.plusSeconds(100));

		assertThat(heartbeat.getName()).isEqualTo("Backup");
		assertThat(heartbeat.isEnabled()).isFalse();
		assertThat(heartbeat.getNextDueAt()).isEqualTo(due);
		assertThat(heartbeat.getUpdatedAt()).isEqualTo(T0.plusSeconds(100));
	}

	@Test
	void aMissWhileTheAlertIsOpenRaisesNothingNew() {
		Heartbeat heartbeat = new Heartbeat(backups, "Nightly backup", 3600, 300, "token", true, T0);

		assertThat(heartbeat.recordMissed(T0.plusSeconds(4000))).isTrue();
		heartbeat.alertRaised(7L);

		assertThat(heartbeat.recordMissed(T0.plusSeconds(8000))).isFalse();
		assertThat(heartbeat.getOutageStartedAt()).isEqualTo(T0.plusSeconds(4000));
		assertThat(heartbeat.recordPing(T0.plusSeconds(9000))).isEqualTo(7L);
		assertThat(heartbeat.getActiveAlertId()).isNull();
	}

	private static ServiceEntity service(long id, String name) {
		AppUser owner = new AppUser("owner@test.local", "Owner", "hash", T0);
		ServiceEntity service = new ServiceEntity(name, null, owner, Criticality.MEDIUM, T0);
		ReflectionTestUtils.setField(service, "id", id);
		return service;
	}
}
