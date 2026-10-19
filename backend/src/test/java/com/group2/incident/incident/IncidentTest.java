package com.group2.incident.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.AppService;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.SlaTarget;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.Severity;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IncidentTest {

	@Test
	@DisplayName("TC-FR-16.1a: responseDueAt and resolutionDueAt are creation time plus each target")
	void dueTimesAreCreationTimePlusTarget() {
		Instant now = Instant.parse("2026-10-19T10:00:00Z");
		AppUser user = new AppUser("engineer", "Dev Engineer", "hash", Role.ENGINEER, now);
		AppService service = new AppService("Payments", "Payments service", user, Criticality.HIGH,
				Map.of(Severity.SEV2, new SlaTarget(60, 480)), now);

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
		AppService service = new AppService("Payments", "Payments service", user, Criticality.HIGH,
				Map.of(Severity.SEV1, new SlaTarget(15, 240)), createdTime);

		SlaTarget copiedTarget = service.targetFor(Severity.SEV1);
		Incident incident = new Incident(
				"Major outage",
				"Total failure",
				service,
				Severity.SEV1,
				IncidentStatus.OPEN,
				user,
				createdTime,
				copiedTarget.responseMinutes(),
				copiedTarget.resolutionMinutes()
		);

		// Service changes its targets later
		Instant later = createdTime.plus(2, ChronoUnit.HOURS);
		AppService updatedService = new AppService("Payments", "Payments service", user, Criticality.HIGH,
				Map.of(Severity.SEV1, new SlaTarget(30, 480)), later);

		assertThat(incident.getResponseTargetMinutes()).isEqualTo(15);
		assertThat(incident.getResolutionTargetMinutes()).isEqualTo(240);
		assertThat(updatedService.targetFor(Severity.SEV1).responseMinutes()).isEqualTo(30);
	}
}
