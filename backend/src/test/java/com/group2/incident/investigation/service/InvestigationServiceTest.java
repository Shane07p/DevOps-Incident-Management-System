package com.group2.incident.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.deploy.repository.DeploymentRepository;
import com.group2.incident.deploy.repository.IncidentDeploymentRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.investigation.dto.InvestigationResponse;
import com.group2.incident.investigation.dto.SuggestedActionDto;
import com.group2.incident.investigation.entity.ActionDecision;
import com.group2.incident.investigation.entity.Investigation;
import com.group2.incident.investigation.entity.InvestigationAction;
import com.group2.incident.investigation.repository.InvestigationActionRepository;
import com.group2.incident.investigation.repository.InvestigationRepository;
import com.group2.incident.investigation.service.AiInvestigationClient.AiEvidenceItem;
import com.group2.incident.investigation.service.AiInvestigationClient.AiHypothesis;
import com.group2.incident.investigation.service.AiInvestigationClient.AiInvestigationResult;
import com.group2.incident.telemetry.service.TelemetryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class InvestigationServiceTest {

	@Mock private IncidentRepository incidentRepository;
	@Mock private TimelineEntryRepository timelineEntryRepository;
	@Mock private DeploymentRepository deploymentRepository;
	@Mock private IncidentDeploymentRepository incidentDeploymentRepository;
	@Mock private TelemetryService telemetryService;
	@Mock private AiInvestigationClient aiInvestigationClient;
	@Mock private InvestigationRepository investigationRepository;
	@Mock private InvestigationActionRepository investigationActionRepository;
	@Mock private AppUserRepository appUserRepository;

	private Clock clock;
	private TransactionTemplate transactionTemplate;
	private InvestigationService service;

	private final Instant fixedTime = Instant.parse("2026-11-19T10:00:00Z");

	@BeforeEach
	void setUp() {
		clock = Clock.fixed(fixedTime, ZoneId.of("UTC"));
		PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
		transactionTemplate = new TransactionTemplate(ptm) {
			@Override
			@SuppressWarnings("unchecked")
			public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
				return action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
			}
		};

		service = new InvestigationService(
				incidentRepository,
				timelineEntryRepository,
				deploymentRepository,
				incidentDeploymentRepository,
				telemetryService,
				aiInvestigationClient,
				investigationRepository,
				investigationActionRepository,
				appUserRepository,
				transactionTemplate,
				clock
		);
	}

	@Test
	void investigateTruncatesLongDescriptionAndTitleAndHandlesFilters() {
		AppUser user = new AppUser("eng@test.local", "Engineer", "hash", fixedTime);
		ServiceEntity svc = new ServiceEntity("Checkout Service", "Checkout description", user, Criticality.HIGH, fixedTime);
		String longDescription = "a".repeat(6000);
		Incident incident = new Incident("Checkout slow", longDescription, svc, Severity.SEV2, IncidentStatus.OPEN, user, fixedTime, 15, 60);

		when(incidentRepository.findWithDetailsById(1L)).thenReturn(Optional.of(incident));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(user));
		when(timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(1L)).thenReturn(List.of());
		when(incidentDeploymentRepository.findDeploymentsByIncidentId(1L)).thenReturn(List.of());
		when(deploymentRepository.findInWindow(any(), any(), any())).thenReturn(List.of());
		when(aiInvestigationClient.isEnabled()).thenReturn(true);

		String longHypothesisTitle = "h".repeat(600);
		String longEvidenceKind = "k".repeat(150);
		when(aiInvestigationClient.investigate(any())).thenReturn(new AiInvestigationResult(
				true,
				"completed",
				"Test summary",
				List.of(new AiHypothesis(longHypothesisTitle, 0.95, List.of("ev-1"), "reason")),
				List.of(new AiEvidenceItem("ev-1", "tool", longEvidenceKind, "sample text")),
				List.of("  Trimmed action  ", "", "   "),
				1,
				10,
				5.0
		));

		when(investigationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		InvestigationResponse resp = service.investigate(1L, 10L);

		assertThat(resp.summary()).isEqualTo("Test summary");
		assertThat(resp.hypotheses()).hasSize(1);
		assertThat(resp.hypotheses().getFirst().title()).hasSize(500);
		assertThat(resp.hypotheses().getFirst().evidence().getFirst().kind()).hasSize(100);
		assertThat(resp.suggestedActions()).hasSize(1);
		assertThat(resp.suggestedActions().getFirst().text()).isEqualTo("Trimmed action");
	}

	@Test
	void investigateHandlesTelemetryFailuresGracefully() {
		AppUser user = new AppUser("eng@test.local", "Engineer", "hash", fixedTime);
		ServiceEntity svc = new ServiceEntity("Orders", "Orders description", user, Criticality.HIGH, fixedTime);
		Incident incident = new Incident("Orders down", null, svc, Severity.SEV1, IncidentStatus.OPEN, user, fixedTime, 15, 60);

		when(incidentRepository.findWithDetailsById(2L)).thenReturn(Optional.of(incident));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(user));
		when(timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(2L)).thenReturn(List.of());
		when(incidentDeploymentRepository.findDeploymentsByIncidentId(2L)).thenReturn(List.of());
		when(deploymentRepository.findInWindow(any(), any(), any())).thenReturn(List.of());
		when(aiInvestigationClient.isEnabled()).thenReturn(true);

		when(telemetryService.queryLogs(any(), any(), any(), any(), any(), any(Integer.class)))
				.thenThrow(new ServiceUnavailableException("No telemetry"));
		when(telemetryService.queryMetric(any(), any(), any(), any(), any()))
				.thenThrow(new ServiceUnavailableException("No telemetry"));

		when(aiInvestigationClient.investigate(any())).thenReturn(new AiInvestigationResult(
				true, "completed", "Summary", List.of(), List.of(), List.of(), 1, 10, 5.0
		));
		when(investigationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		InvestigationResponse resp = service.investigate(2L, 10L);
		assertThat(resp.summary()).isEqualTo("Summary");
	}

	@Test
	void investigateFailsWhenAiDisabled() {
		AppUser user = new AppUser("eng@test.local", "Engineer", "hash", fixedTime);
		ServiceEntity svc = new ServiceEntity("Orders", "Orders description", user, Criticality.HIGH, fixedTime);
		Incident incident = new Incident("Orders down", "Desc", svc, Severity.SEV1, IncidentStatus.OPEN, user, fixedTime, 15, 60);

		when(incidentRepository.findWithDetailsById(3L)).thenReturn(Optional.of(incident));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(user));
		when(aiInvestigationClient.isEnabled()).thenReturn(false);

		assertThatThrownBy(() -> service.investigate(3L, 10L))
				.isInstanceOf(ServiceUnavailableException.class)
				.hasMessageContaining("AI investigation service is not set up");
	}

	@Test
	void getLatestThrowsNotFoundWhenIncidentOrInvestigationMissing() {
		when(incidentRepository.existsById(99L)).thenReturn(false);
		assertThatThrownBy(() -> service.getLatest(99L))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("Incident 99 not found");

		when(incidentRepository.existsById(1L)).thenReturn(true);
		when(investigationRepository.findTopByIncidentIdOrderByCreatedAtDescIdDesc(1L)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.getLatest(1L))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("No investigation found for incident 1");
	}

	@Test
	void decideActionSuccessWithRejected() {
		AppUser actor = new AppUser("eng@test.local", "Engineer", "hash", fixedTime);
		ServiceEntity svc = new ServiceEntity("Orders", "Orders description", actor, Criticality.HIGH, fixedTime);
		Incident incident = new Incident("Orders down", "Desc", svc, Severity.SEV1, IncidentStatus.OPEN, actor, fixedTime, 15, 60);
		Investigation inv = new Investigation(incident, "Summary", "completed", 1, 10, 5.0, fixedTime);
		InvestigationAction action = new InvestigationAction(inv, "Restart service");

		when(investigationRepository.findById(1L)).thenReturn(Optional.of(inv));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(actor));
		when(investigationActionRepository.recordDecision(eq(2L), eq(1L), eq(ActionDecision.REJECTED), eq(actor), any()))
				.thenReturn(1);
		when(investigationActionRepository.findByIdAndInvestigationId(2L, 1L)).thenReturn(Optional.of(action));

		SuggestedActionDto dto = service.decideAction(1L, 2L, ActionDecision.REJECTED, 10L);

		assertThat(dto.text()).isEqualTo("Restart service");
		ArgumentCaptor<TimelineEntry> captor = ArgumentCaptor.forClass(TimelineEntry.class);
		verify(timelineEntryRepository).save(captor.capture());
		assertThat(captor.getValue().getEventType()).isEqualTo(TimelineEventType.ACTION_REJECTED);
		assertThat(captor.getValue().getDetails()).contains("Rejected AI suggested action: Restart service");
	}

	@Test
	void decideActionThrowsConflictWhenAlreadyDecided() {
		AppUser actor = new AppUser("eng@test.local", "Engineer", "hash", fixedTime);
		ServiceEntity svc = new ServiceEntity("Orders", "Orders description", actor, Criticality.HIGH, fixedTime);
		Incident incident = new Incident("Orders down", "Desc", svc, Severity.SEV1, IncidentStatus.OPEN, actor, fixedTime, 15, 60);
		Investigation inv = new Investigation(incident, "Summary", "completed", 1, 10, 5.0, fixedTime);
		InvestigationAction action = new InvestigationAction(inv, "Restart service");

		when(investigationRepository.findById(1L)).thenReturn(Optional.of(inv));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(actor));
		when(investigationActionRepository.recordDecision(eq(2L), eq(1L), eq(ActionDecision.ACCEPTED), eq(actor), any()))
				.thenReturn(0);
		when(investigationActionRepository.findByIdAndInvestigationId(2L, 1L)).thenReturn(Optional.of(action));

		assertThatThrownBy(() -> service.decideAction(1L, 2L, ActionDecision.ACCEPTED, 10L))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("Action 2 has already been decided");
	}

	@Test
	void decideActionThrowsNotFoundWhenActionMissing() {
		AppUser actor = new AppUser("eng@test.local", "Engineer", "hash", fixedTime);
		ServiceEntity svc = new ServiceEntity("Orders", "Orders description", actor, Criticality.HIGH, fixedTime);
		Incident incident = new Incident("Orders down", "Desc", svc, Severity.SEV1, IncidentStatus.OPEN, actor, fixedTime, 15, 60);
		Investigation inv = new Investigation(incident, "Summary", "completed", 1, 10, 5.0, fixedTime);

		when(investigationRepository.findById(1L)).thenReturn(Optional.of(inv));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(actor));
		when(investigationActionRepository.recordDecision(eq(99L), eq(1L), eq(ActionDecision.ACCEPTED), eq(actor), any()))
				.thenReturn(0);
		when(investigationActionRepository.findByIdAndInvestigationId(99L, 1L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.decideAction(1L, 99L, ActionDecision.ACCEPTED, 10L))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("Action 99 not found for investigation 1");
	}
}
