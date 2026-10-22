package com.group2.incident.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.SlaTarget;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.catalog.service.OnCallService;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.dto.IncidentSummaryResponse;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.Severity;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.incident.service.IncidentService;
import com.group2.incident.notification.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-10-19T10:00:00Z");

	@Mock
	private IncidentRepository incidentRepository;

	@Mock
	private TimelineEntryRepository timelineEntryRepository;

	@Mock
	private ServiceRepository serviceRepository;

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private AuditService auditService;

	@Mock
	private OnCallService onCallService;

	@Mock
	private NotificationService notificationService;

	private Clock fixedClock;
	private IncidentService incidentService;

	private AppUser user;
	private ServiceEntity service;

	@BeforeEach
	void setUp() {
		fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
		incidentService = new IncidentService(
				incidentRepository,
				timelineEntryRepository,
				serviceRepository,
				appUserRepository,
				auditService,
				onCallService,
				notificationService,
				fixedClock
		);

		user = new AppUser(1L, "engineer", "Dev Engineer", "hash", Role.ENGINEER, FIXED_NOW);
		service = new ServiceEntity(10L, "Payments", "Payments service", user, Criticality.HIGH,
				Map.of(
						Severity.SEV1, new SlaTarget(15, 240),
						Severity.SEV2, new SlaTarget(60, 480)
				), FIXED_NOW);
	}

	@Test
	@DisplayName("createIncident saves incident, timeline and audit in one flow")
	void createIncidentSuccess() {
		when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
		when(serviceRepository.findWithDetailsById(10L)).thenReturn(Optional.of(service));
		when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> {
			Incident inc = inv.getArgument(0);
			return new Incident(7L, inc.getTitle(), inc.getDescription(), inc.getService(),
					inc.getSeverity(), inc.getStatus(), inc.getReporter(), inc.getCreatedAt(),
					inc.getResponseTargetMinutes(), inc.getResolutionTargetMinutes());
		});
		when(timelineEntryRepository.save(any(TimelineEntry.class))).thenAnswer(inv -> {
			TimelineEntry te = inv.getArgument(0);
			return new TimelineEntry(1L, te.getIncident(), te.getActor(), te.getEventType(),
					te.getDetails(), te.getRecordedAt(), te.getCorrectsEntry());
		});

		CreateIncidentRequest request = new CreateIncidentRequest(
				"Checkout failure",
				"Database timeouts",
				10L,
				Severity.SEV1
		);

		IncidentResponse response = incidentService.createIncident(request, 1L);

		assertThat(response.id()).isEqualTo(7L);
		assertThat(response.reference()).isEqualTo("INC-7");
		assertThat(response.title()).isEqualTo("Checkout failure");
		assertThat(response.severity()).isEqualTo(Severity.SEV1);
		assertThat(response.status()).isEqualTo(IncidentStatus.OPEN);
		assertThat(response.service().name()).isEqualTo("Payments");
		assertThat(response.reporter().displayName()).isEqualTo("Dev Engineer");
		assertThat(response.responseTargetMinutes()).isEqualTo(15);
		assertThat(response.resolutionTargetMinutes()).isEqualTo(240);

		ArgumentCaptor<Incident> incidentCaptor = ArgumentCaptor.forClass(Incident.class);
		verify(incidentRepository).save(incidentCaptor.capture());
		assertThat(incidentCaptor.getValue().getTitle()).isEqualTo("Checkout failure");

		verify(auditService).record(any(), any(), any(), eq(7L), any());
	}

	@Test
	@DisplayName("createIncident throws FieldValidationException when service does not exist")
	void createIncidentUnknownServiceThrows() {
		when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
		when(serviceRepository.findWithDetailsById(99L)).thenReturn(Optional.empty());

		CreateIncidentRequest request = new CreateIncidentRequest(
				"Checkout failure",
				"Database timeouts",
				99L,
				Severity.SEV1
		);

		assertThatThrownBy(() -> incidentService.createIncident(request, 1L))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("Service not found");
	}

	@Test
	@DisplayName("createIncident throws FieldValidationException when SLA target is missing for severity")
	void createIncidentMissingSlaTargetThrows() {
		when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
		when(serviceRepository.findWithDetailsById(10L)).thenReturn(Optional.of(service));

		CreateIncidentRequest request = new CreateIncidentRequest(
				"Low priority issue",
				"Minor bug",
				10L,
				Severity.SEV4
		);

		assertThatThrownBy(() -> incidentService.createIncident(request, 1L))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("no SLA target for SEV4");
	}

	@Test
	@DisplayName("getIncident returns incident with timeline")
	void getIncidentReturnsDetails() {
		Incident incident = new Incident(7L, "Checkout failure", "Database timeouts", service,
				Severity.SEV1, IncidentStatus.OPEN, user, FIXED_NOW, 15, 240);
		TimelineEntry te = new TimelineEntry(1L, incident, user,
				com.group2.incident.incident.entity.TimelineEventType.INCIDENT_CREATED,
				"Reported as SEV1", FIXED_NOW, null);

		when(incidentRepository.findWithDetailsById(7L)).thenReturn(Optional.of(incident));
		when(timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(7L)).thenReturn(List.of(te));

		IncidentResponse response = incidentService.getIncident(7L);

		assertThat(response.id()).isEqualTo(7L);
		assertThat(response.reference()).isEqualTo("INC-7");
		assertThat(response.timeline()).hasSize(1);
	}

	@Test
	@DisplayName("getIncident throws NotFoundException when incident does not exist")
	void getIncidentNotFoundThrows() {
		when(incidentRepository.findWithDetailsById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> incidentService.getIncident(999L))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("Incident 999 not found");
	}
}
