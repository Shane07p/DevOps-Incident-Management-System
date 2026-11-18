package com.group2.incident.incident.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.entity.SlaTarget;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IncidentTest {

	@Test
	@DisplayName("TC-FR-16.1a: responseDueAt and resolutionDueAt are creation time plus each target")
	void dueTimesAreCreationTimePlusTarget() {
		Instant now = Instant.parse("2026-10-19T10:00:00Z");
		AppUser user = new AppUser("engineer@test.local", "Dev Engineer", "hash", now);
		ServiceEntity service = new ServiceEntity("Payments", "Payments service", user, Criticality.HIGH, now);
		service.putTarget(Severity.SEV2, 60, 480);

		Incident incident = new Incident(
				"Checkout failing",
				"Database connection timeout",
				service,
				Severity.SEV2,
				IncidentStatus.OPEN,
				user,
				now,
				60,
				480
		);

		assertThat(incident.getResponseDueAt()).isEqualTo(now.plus(60, ChronoUnit.MINUTES));
		assertThat(incident.getResolutionDueAt()).isEqualTo(now.plus(480, ChronoUnit.MINUTES));
	}

	@Test
	@DisplayName("TC-FR-16.3a: incident keeps the copied targets when service targets change later")
	void incidentKeepsCopiedTargetsEvenIfServiceChanges() {
		Instant createdTime = Instant.parse("2026-10-19T10:00:00Z");
		AppUser user = new AppUser("engineer@test.local", "Dev Engineer", "hash", createdTime);
		ServiceEntity service = new ServiceEntity("Payments", "Payments service", user, Criticality.HIGH, createdTime);
		service.putTarget(Severity.SEV1, 15, 240);

		SlaTarget copiedTarget = service.getTargets().get(Severity.SEV1);
		Incident incident = new Incident(
				"Major outage",
				"Total failure",
				service,
				Severity.SEV1,
				IncidentStatus.OPEN,
				user,
				createdTime,
				copiedTarget.getResponseMinutes(),
				copiedTarget.getResolutionMinutes()
		);

		// The same service changes its targets later
		service.putTarget(Severity.SEV1, 30, 480);

		assertThat(incident.getResponseTargetMinutes()).isEqualTo(15);
		assertThat(incident.getResolutionTargetMinutes()).isEqualTo(240);
		assertThat(service.getTargets().get(Severity.SEV1).getResponseMinutes()).isEqualTo(30);
	}
	@Test
	@DisplayName("An incident takes its number once (D48)")
	void anIncidentTakesItsNumberOnce() {
		Instant now = Instant.parse("2026-10-19T10:00:00Z");
		AppUser user = new AppUser("engineer@test.local", "Dev Engineer", "hash", now);
		ServiceEntity service = new ServiceEntity("Payments", "Payments service", user, Criticality.HIGH, now);
		Incident incident = new Incident("Checkout failing", "Timeouts", service, Severity.SEV2, IncidentStatus.OPEN, user, now, 60, 480);

		incident.assignNumber(7L);

		assertThat(incident.getReference()).isEqualTo("INC-7");
		assertThatThrownBy(() -> incident.assignNumber(8L))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Incident 7 already has a number");
		assertThat(incident.getReference()).isEqualTo("INC-7");
	}

	@Test
	@DisplayName("Acknowledge transitions OPEN to ACKNOWLEDGED, sets assignee and acknowledgedAt")
	void acknowledgeOpenIncidentSetsStatusAssigneeAndTimestamp() {
		Instant created = Instant.parse("2026-10-19T10:00:00Z");
		Instant ackTime = Instant.parse("2026-10-19T10:10:00Z");
		AppUser reporter = new AppUser("reporter@test.local", "Reporter", "hash", created);
		AppUser responder = new AppUser("responder@test.local", "Responder", "hash", created);
		ServiceEntity service = new ServiceEntity("Payments", "Payments service", reporter, Criticality.HIGH, created);
		Incident incident = new Incident("Checkout failing", "Timeouts", service, Severity.SEV2, IncidentStatus.OPEN, reporter, created, 60, 480);

		incident.acknowledge(responder, ackTime);

		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(incident.getAssignee()).isEqualTo(responder);
		assertThat(incident.getAcknowledgedAt()).isEqualTo(ackTime);
	}

	@Test
	@DisplayName("Acknowledge on non-OPEN incident throws IllegalStateException")
	void acknowledgeNonOpenIncidentThrows() {
		Instant created = Instant.parse("2026-10-19T10:00:00Z");
		AppUser user = new AppUser("user@test.local", "User", "hash", created);
		ServiceEntity service = new ServiceEntity("Payments", "Payments service", user, Criticality.HIGH, created);
		Incident incident = new Incident("Checkout failing", "Timeouts", service, Severity.SEV2, IncidentStatus.ACKNOWLEDGED, user, created, 60, 480);

		assertThatThrownBy(() -> incident.acknowledge(user, created))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Only OPEN incidents can be acknowledged");
	}

	@Test
	@DisplayName("transitionTo updates incident status")
	void transitionToUpdatesStatus() {
		Instant created = Instant.parse("2026-10-19T10:00:00Z");
		AppUser user = new AppUser("user@test.local", "User", "hash", created);
		ServiceEntity service = new ServiceEntity("Payments", "Payments service", user, Criticality.HIGH, created);
		Incident incident = new Incident("Checkout failing", "Timeouts", service, Severity.SEV2, IncidentStatus.ACKNOWLEDGED, user, created, 60, 480);

		incident.transitionTo(IncidentStatus.INVESTIGATING, created.plusSeconds(60));
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.INVESTIGATING);

		incident.transitionTo(IncidentStatus.IDENTIFIED, created.plusSeconds(120));
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.IDENTIFIED);
		assertThat(incident.getResolvedAt()).isNull();
	}

	@Test
	@DisplayName("RESOLVED stops the resolution clock once; closing keeps that time")
	void resolvingRecordsTheTimeOnce() {
		Instant created = Instant.parse("2026-10-19T10:00:00Z");
		AppUser user = new AppUser("user@test.local", "User", "hash", created);
		ServiceEntity service = new ServiceEntity("Payments", "Payments service", user, Criticality.HIGH, created);
		Incident incident = new Incident("Checkout failing", "Timeouts", service, Severity.SEV2, IncidentStatus.MONITORING, user, created, 60, 480);

		incident.transitionTo(IncidentStatus.RESOLVED, created.plusSeconds(600));
		incident.transitionTo(IncidentStatus.CLOSED, created.plusSeconds(900));

		assertThat(incident.getResolvedAt()).isEqualTo(created.plusSeconds(600));
		assertThat(incident.getResolutionDueAt()).isEqualTo(created.plusSeconds(480 * 60));
	}

}
