package com.group2.incident.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.group2.incident.support.TestcontainersConfiguration;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies that migration V28 correctly backfills historical notifications and classifies ambiguous ones as SYSTEM.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationMigrationTest {

	private static final String SCHEMA = "migration_v28_check";

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc = new JdbcTemplate(dataSource);
		// Migrate isolated schema to V27 (the exact schema state prior to V28)
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(SCHEMA)
				.target("27")
				.cleanDisabled(false)
				.load()
				.migrate();
	}

	@AfterEach
	void tearDown() {
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(SCHEMA)
				.cleanDisabled(false)
				.load()
				.clean();
	}

	@Test
	@DisplayName("V28 correctly classifies historical notifications and falls back to SYSTEM for ambiguous ones")
	void v28BackfillClassifiesHistoricalNotifications() {
		long orgId = jdbc.queryForObject("""
				INSERT INTO %s.organization (name, slug, created_at)
				VALUES ('Dev org', 'dev-org', now()) RETURNING id
				""".formatted(SCHEMA), Long.class);

		long onCallUser = insertUser("oncall");
		long escalatedUser = insertUser("escalated");
		long reassignedUser = insertUser("reassigned");
		long roleUser = insertUser("roleuser");
		long ambiguousUser = insertUser("ambiguous");

		long serviceId = jdbc.queryForObject("""
				INSERT INTO %s.service (org_id, name, owner_id, criticality, created_at, updated_at)
				VALUES (?, 'Payments', ?, 'HIGH', now(), now()) RETURNING id
				""".formatted(SCHEMA), Long.class, orgId, onCallUser);

		long incidentId = jdbc.queryForObject("""
				INSERT INTO %s.incident (org_id, number, title, description, service_id, severity, status, reporter_id, created_at, response_target_minutes, resolution_target_minutes)
				VALUES (?, 1, 'Payment Gateway Timeout', '504 Gateway Timeout', ?, 'SEV1', 'OPEN', ?, now(), 15, 120) RETURNING id
				""".formatted(SCHEMA), Long.class, orgId, serviceId, onCallUser);

		// 1. Initial on-call assignment timeline entry: ASSIGNED, actor_id IS NULL, "Assigned to oncall" (2 hours ago)
		jdbc.update("""
				INSERT INTO %s.timeline_entry (org_id, incident_id, actor_id, event_type, details, recorded_at)
				VALUES (?, ?, NULL, 'ASSIGNED', 'Assigned to oncall', now() - interval '2 hours')
				""".formatted(SCHEMA), orgId, incidentId);

		// 2. Reassignment timeline entry: REASSIGNED, actor_id onCallUser, "Reassigned from oncall to reassigned" (1 hour ago)
		jdbc.update("""
				INSERT INTO %s.timeline_entry (org_id, incident_id, actor_id, event_type, details, recorded_at)
				VALUES (?, ?, ?, 'REASSIGNED', 'Reassigned from oncall to reassigned', now() - interval '1 hour')
				""".formatted(SCHEMA), orgId, incidentId, onCallUser);

		// 3. Second reassignment timeline entry back to the original on-call user: REASSIGNED (30 minutes ago)
		jdbc.update("""
				INSERT INTO %s.timeline_entry (org_id, incident_id, actor_id, event_type, details, recorded_at)
				VALUES (?, ?, ?, 'REASSIGNED', 'Reassigned from reassigned to oncall', now() - interval '30 minutes')
				""".formatted(SCHEMA), orgId, incidentId, reassignedUser);

		// 4. Role assignment timeline entry: ROLE_ASSIGNED, actor_id onCallUser, "Assigned role COMMS to roleuser"
		jdbc.update("""
				INSERT INTO %s.timeline_entry (org_id, incident_id, actor_id, event_type, details, recorded_at)
				VALUES (?, ?, ?, 'ROLE_ASSIGNED', 'Assigned role COMMS to roleuser', now() - interval '20 minutes')
				""".formatted(SCHEMA), orgId, incidentId, onCallUser);

		// Insert historical notifications (under V27 schema: NO kind column exists):
		// 1. Historical initial on-call notification (same user, 2 hours ago -> should be PAGE)
		long notifOnCallInitial = insertNotification(orgId, onCallUser, incidentId,
				"You have been assigned to incident INC-1: Payment Gateway Timeout", "now() - interval '2 hours'");

		// 2. Historical escalation notification
		long notifEscalation = insertNotification(orgId, escalatedUser, incidentId,
				"Incident INC-1 was escalated to you (level 1): Payment Gateway Timeout", "now() - interval '90 minutes'");

		// 3. Historical reassignment notification to another user (1 hour ago -> should be REASSIGNMENT)
		long notifReassignment = insertNotification(orgId, reassignedUser, incidentId,
				"You have been assigned to incident INC-1: Payment Gateway Timeout", "now() - interval '1 hour'");

		// 4. Historical reassignment notification back to the same onCallUser (30 minutes ago -> should be REASSIGNMENT, NOT PAGE)
		long notifOnCallReassignment = insertNotification(orgId, onCallUser, incidentId,
				"You have been assigned to incident INC-1: Payment Gateway Timeout", "now() - interval '30 minutes'");

		// 5. Historical role assignment notification
		long notifRole = insertNotification(orgId, roleUser, incidentId,
				"You have been assigned the role COMMS on incident INC-1: Payment Gateway Timeout", "now() - interval '20 minutes'");

		// 6. Ambiguous notification (assignment message without matching ASSIGNED/REASSIGNED timeline entry)
		long notifAmbiguous = insertNotification(orgId, ambiguousUser, incidentId,
				"You have been assigned to incident INC-1: Payment Gateway Timeout", "now() - interval '10 minutes'");

		// 7. General notification without an incident
		long notifGeneral = insertNotification(orgId, onCallUser, null,
				"Scheduled system maintenance tonight at 23:00 UTC", "now() - interval '5 minutes'");

		// Migrate to V28
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(SCHEMA)
				.target("28")
				.load()
				.migrate();

		// Verify classifications:
		assertThat(getNotificationKind(notifOnCallInitial)).isEqualTo("PAGE");
		assertThat(getNotificationKind(notifEscalation)).isEqualTo("PAGE");
		assertThat(getNotificationKind(notifReassignment)).isEqualTo("REASSIGNMENT");
		assertThat(getNotificationKind(notifOnCallReassignment)).isEqualTo("REASSIGNMENT");
		assertThat(getNotificationKind(notifRole)).isEqualTo("ROLE_ASSIGNMENT");
		assertThat(getNotificationKind(notifAmbiguous)).isEqualTo("SYSTEM");
		assertThat(getNotificationKind(notifGeneral)).isEqualTo("SYSTEM");

		// Verify default on new inserts without kind
		long newNotif = jdbc.queryForObject("""
				INSERT INTO %s.notification (org_id, recipient_id, message, created_at)
				VALUES (?, ?, 'New unclassified message', now()) RETURNING id
				""".formatted(SCHEMA), Long.class, orgId, onCallUser);
		assertThat(getNotificationKind(newNotif)).isEqualTo("SYSTEM");

		// Verify check constraint rejects invalid kind
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO %s.notification (org_id, recipient_id, message, kind, created_at)
				VALUES (?, ?, 'Invalid kind', 'UNKNOWN', now())
				""".formatted(SCHEMA), orgId, onCallUser))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("notification_kind_check");
	}

	@Test
	@DisplayName("Regression: two notifications initially preferring the same event both match distinct events within five seconds")
	void rapidEventsWithinFiveSecondsCorrelateDeterministically() {
		long orgId = jdbc.queryForObject("""
				INSERT INTO %s.organization (name, slug, created_at)
				VALUES ('Dev org', 'dev-org', now()) RETURNING id
				""".formatted(SCHEMA), Long.class);

		long engineer = insertUser("rapideng");

		long serviceId = jdbc.queryForObject("""
				INSERT INTO %s.service (org_id, name, owner_id, criticality, created_at, updated_at)
				VALUES (?, 'Auth', ?, 'HIGH', now(), now()) RETURNING id
				""".formatted(SCHEMA), Long.class, orgId, engineer);

		long incidentId = jdbc.queryForObject("""
				INSERT INTO %s.incident (org_id, number, title, description, service_id, severity, status, reporter_id, created_at, response_target_minutes, resolution_target_minutes)
				VALUES (?, 1, 'Auth Failure', 'Token expired', ?, 'SEV1', 'OPEN', ?, now(), 15, 120) RETURNING id
				""".formatted(SCHEMA), Long.class, orgId, serviceId, engineer);

		// Event 1: Initial on-call declaration at T = 0 (20 seconds ago)
		jdbc.update("""
				INSERT INTO %s.timeline_entry (org_id, incident_id, actor_id, event_type, details, recorded_at)
				VALUES (?, ?, NULL, 'ASSIGNED', 'Assigned to rapideng', now() - interval '20 seconds')
				""".formatted(SCHEMA), orgId, incidentId);

		// Event 2: Rapid reassignment back to the same engineer at T = 3s (17 seconds ago, within 5s of Event 1)
		jdbc.update("""
				INSERT INTO %s.timeline_entry (org_id, incident_id, actor_id, event_type, details, recorded_at)
				VALUES (?, ?, ?, 'REASSIGNED', 'Reassigned from backup to rapideng', now() - interval '17 seconds')
				""".formatted(SCHEMA), orgId, incidentId, engineer);

		// Notification 1: Created at T = 0.5s (19.5 seconds ago) -> closer to Event 1 (0.5s) than Event 2 (2.5s)
		long notif1 = insertNotification(orgId, engineer, incidentId,
				"You have been assigned to incident INC-1: Auth Failure", "now() - interval '19.5 seconds'");

		// Notification 2: Created at T = 1.0s (19 seconds ago) -> also closer to Event 1 (1.0s) than Event 2 (2.0s)
		// Both notifications initially prefer Event 1. 1-to-1 matching must match notif1 -> Event 1, and notif2 -> Event 2.
		long notif2 = insertNotification(orgId, engineer, incidentId,
				"You have been assigned to incident INC-1: Auth Failure", "now() - interval '19 seconds'");

		// Migrate to V28
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(SCHEMA)
				.target("28")
				.load()
				.migrate();

		// Notification 1 matches Event 1 (ASSIGNED -> PAGE)
		// Notification 2 matches Event 2 (REASSIGNED -> REASSIGNMENT) rather than being dropped as SYSTEM
		assertThat(getNotificationKind(notif1)).isEqualTo("PAGE");
		assertThat(getNotificationKind(notif2)).isEqualTo("REASSIGNMENT");
	}

	private long insertUser(String name) {
		return jdbc.queryForObject("""
				INSERT INTO %s.app_user (email, display_name, password_hash, created_at)
				VALUES (? || '@test.local', ?, '{noop}x', now()) RETURNING id
				""".formatted(SCHEMA), Long.class, name, name);
	}

	private long insertNotification(long orgId, long recipientId, Long incidentId, String message, String createdAtSql) {
		return jdbc.queryForObject("""
				INSERT INTO %s.notification (org_id, recipient_id, incident_id, message, created_at)
				VALUES (?, ?, ?, ?, %s) RETURNING id
				""".formatted(SCHEMA, createdAtSql), Long.class, orgId, recipientId, incidentId, message);
	}

	private String getNotificationKind(long notificationId) {
		return jdbc.queryForObject(
				"SELECT kind FROM " + SCHEMA + ".notification WHERE id = ?",
				String.class,
				notificationId
		);
	}
}

