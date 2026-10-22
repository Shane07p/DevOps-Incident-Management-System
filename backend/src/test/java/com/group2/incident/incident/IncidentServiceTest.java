package com.group2.incident.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.AppService;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.SlaTarget;
import com.group2.incident.catalog.repository.AppServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.dto.IncidentSummaryResponse;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.Severity;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.incident.service.IncidentService;
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
	private AppServiceRepository appServiceRepository;

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private AuditService auditService;

	private Clock fixedClock;
	private IncidentService incidentService;

	private AppUser user;
	private AppService service;

	@BeforeEach
	void setUp() {
		fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
		incidentService = new IncidentService(
				incidentRepository,
				timelineEntryRepository,
				appServiceRepository,
				appUserRepository,
				auditService,
				fixedClock
		);

		user = new AppUser(1L, "engineer", "Dev Engineer", "hash", Role.ENGINEER, FIXED_NOW);
		service = new AppService(10L, "Payments", "Payments service", user, Criticality.HIGH,
				Map.of(
						Severity.SEV1, new SlaTarget(15, 240),
						Severity.SEV2, new SlaTarget(60, 480)
				), FIXED_NOW);
	}

	@Test
	@DisplayName("createIncident saves incident, timeline and audit in one flow")
	void createIncidentSuccess() {
		when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
		when(appServiceRepository.findWithDetailsById(10L)).thenReturn(Optional.of(service));
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
				Severity.SEV2
		);

		IncidentResponse response = incidentService.createIncident(request, 1L);

		assertThat(response.title()).isEqualTo("Checkout failure");
		assertThat(response.status()).isEqualTo(IncidentStatus.OPEN);
		assertThat(response.severity()).isEqualTo(Severity.SEV2);
		assertThat(response.service().name()).isEqualTo("Payments");
		assertThat(response.reporter().displayName()).isEqualTo("Dev Engineer");
		assertThat(response.responseTargetMinutes()).isEqualTo(60);
		assertThat(response.resolutionTargetMinutes()).isEqualTo(480);
		assertThat(response.timeline()).hasSize(1);
		assertThat(response.timeline().get(0).eventType()).isEqualTo(TimelineEventType.INCIDENT_CREATED);

		ArgumentCaptor<Incident> incidentCaptor = ArgumentCaptor.forClass(Incident.class);
		verify(incidentRepository).save(incidentCaptor.capture());
		assertThat(incidentCaptor.getValue().getTitle()).isEqualTo("Checkout failure");
		assertThat(incidentCaptor.getValue().getResponseTargetMinutes()).isEqualTo(60);

		verify(auditService).record(any(), any(), any(), any(Long.class), any());
	}

	@Test
	@DisplayName("createIncident throws FieldValidationException when service is not found")
	void createIncidentUnknownService() {
		when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
		when(appServiceRepository.findWithDetailsById(99L)).thenReturn(Optional.empty());

		CreateIncidentRequest request = new CreateIncidentRequest(
				"Title", "Desc", 99L, Severity.SEV1
		);

		assertThatThrownBy(() -> incidentService.createIncident(request, 1L))
				.isInstanceOf(FieldValidationException.class)
				.hasMessage("Service not found");
	}

	@Test
	@DisplayName("createIncident throws FieldValidationException when service lacks target for severity")
	void createIncidentMissingSeverityTarget() {
		when(appUserRepository.findById(1L)).thenReturn(Optional.of(user));
		when(appServiceRepository.findWithDetailsById(10L)).thenReturn(Optional.of(service));

		CreateIncidentRequest request = new CreateIncidentRequest(
				"Title", "Desc", 10L, Severity.SEV4
		);

		assertThatThrownBy(() -> incidentService.createIncident(request, 1L))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("has no SLA target for SEV4");
	}

	@Test
	@DisplayName("getIncident throws NotFoundException when incident does not exist")
	void getIncidentNotFound() {
		when(incidentRepository.findWithDetailsById(123L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> incidentService.getIncident(123L))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("Incident 123 not found");
	}

	@Test
	@DisplayName("listIncidents returns summaries newest first")
	void listIncidentsSummary() {
		Incident inc = new Incident(1L, "Inc 1", "Desc", service, Severity.SEV1,
				IncidentStatus.OPEN, user, FIXED_NOW, 15, 240);
		when(incidentRepository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of(inc));

		List<IncidentSummaryResponse> list = incidentService.listIncidents();
		assertThat(list).hasSize(1);
		assertThat(list.get(0).title()).isEqualTo("Inc 1");
		assertThat(list.get(0).serviceName()).isEqualTo("Payments");
	}
}
