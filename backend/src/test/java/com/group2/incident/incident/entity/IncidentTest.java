package com.group2.incident.incident.entity;

import static org.assertj.core.api.Assertions.assertThat;

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
		AppUser user = new AppUser("engineer", "Dev Engineer", "hash", Role.ENGINEER, now);
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
		AppUser user = new AppUser("engineer", "Dev Engineer", "hash", Role.ENGINEER, createdTime);
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
}
