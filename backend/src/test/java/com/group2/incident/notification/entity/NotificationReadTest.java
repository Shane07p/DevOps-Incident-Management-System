package com.group2.incident.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.auth.entity.AppUser;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** A notification is unread until first read, and that first read stays its read time (FR-08). */
class NotificationReadTest {

	private static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");

	private final AppUser recipient = new AppUser("eng@test.local", "Engineer", "hash", T0);

	@Test
	void theFirstReadIsKept() {
		Notification notification = new Notification(recipient, null, NotificationKind.PAGE, "INC-7 was escalated to you", T0);

		assertThat(notification.isRead()).isFalse();
		notification.markRead(T0.plusSeconds(60));
		notification.markRead(T0.plusSeconds(600));

		assertThat(notification.isRead()).isTrue();
		assertThat(notification.getReadAt()).isEqualTo(T0.plusSeconds(60));
	}

	@Test
	void aNotificationWithoutAKindIsASystemOne() {
		assertThat(new Notification(recipient, null, null, "Maintenance tonight", T0).getKind()).isEqualTo(NotificationKind.SYSTEM);
		assertThat(new Notification(recipient, null, "Maintenance tonight", T0).getKind()).isEqualTo(NotificationKind.SYSTEM);
	}
}
