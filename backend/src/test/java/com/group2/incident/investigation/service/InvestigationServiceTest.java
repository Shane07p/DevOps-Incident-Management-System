package com.group2.incident.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.deploy.entity.Deployment;
import com.group2.incident.deploy.repository.DeploymentRepository;
import com.group2.incident.deploy.repository.IncidentDeploymentRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.investigation.dto.InvestigationEvidenceDto;
import com.group2.incident.investigation.dto.InvestigationHypothesisDto;
import com.group2.incident.investigation.dto.InvestigationResponse;
import com.group2.incident.investigation.dto.SuggestedActionDto;
import com.group2.incident.investigation.entity.ActionDecision;
import com.group2.incident.investigation.entity.Investigation;
import com.group2.incident.investigation.entity.InvestigationAction;
import com.group2.incident.investigation.repository.InvestigationActionRepository;
import com.group2.incident.investigation.repository.InvestigationRepository;
import com.group2.incident.investigation.service.AiInvestigationClient.AiEvidenceItem;
import com.group2.incident.investigation.service.AiInvestigationClient.AiHypothesis;
import com.group2.incident.investigation.service.AiInvestigationClient.AiInvestigationRequest;
import com.group2.incident.investigation.service.AiInvestigationClient.AiInvestigationResult;
import com.group2.incident.investigation.service.AiInvestigationClient.DeployBundleItem;
import com.group2.incident.investigation.service.AiInvestigationClient.EvidenceBundle;
import com.group2.incident.investigation.service.AiInvestigationClient.LogBundleItem;
import com.group2.incident.investigation.service.AiInvestigationClient.MetricBundlePoint;
import com.group2.incident.telemetry.dto.LogEntryResponse;
import com.group2.incident.telemetry.dto.MetricDataPoint;
import com.group2.incident.telemetry.dto.MetricSeriesResponse;
import com.group2.incident.telemetry.service.TelemetryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
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
				clock,
				transactionTemplate
		);
	}

	@Test
	void investigateTruncatesLongDescriptionAndTitleAndHandlesFilters() {
		AppUser user = new AppUser("eng@test.local", "Engineer", "hash", fixedTime);
		ReflectionTestUtils.setField(user, "id", 10L);
		ServiceEntity svc = new ServiceEntity("Checkout Service", "Checkout description", user, Criticality.HIGH, fixedTime);
		ReflectionTestUtils.setField(svc, "id", 5L);
		String longDescription = "a".repeat(6000);
		Incident incident = new Incident("Checkout slow", longDescription, svc, Severity.SEV2, IncidentStatus.OPEN, user, fixedTime, 15, 60);
		ReflectionTestUtils.setField(incident, "id", 1L);
		ReflectionTestUtils.setField(incident, "number", 42L);

		when(incidentRepository.findWithDetailsById(1L)).thenReturn(Optional.of(incident));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(user));
		when(timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(1L)).thenReturn(List.of());
		when(incidentDeploymentRepository.findDeploymentsByIncidentId(1L)).thenReturn(List.of());
		when(deploymentRepository.findInWindow(anyLong(), any(), any())).thenReturn(List.of());
		when(telemetryService.queryLogs(anyLong(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
		when(telemetryService.queryMetric(anyLong(), any(), any(), any(), any()))
				.thenAnswer(inv -> new MetricSeriesResponse(
						inv.getArgument(1), 5L, "Checkout Service", fixedTime.minusSeconds(3600), fixedTime, 60, List.of()
				));
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

		when(investigationRepository.saveAndFlush(any())).thenAnswer(inv -> {
			Investigation i = inv.getArgument(0);
			ReflectionTestUtils.setField(i, "id", 100L);
			return i;
		});

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
		ReflectionTestUtils.setField(user, "id", 10L);
		ServiceEntity svc = new ServiceEntity("Orders", "Orders description", user, Criticality.HIGH, fixedTime);
		ReflectionTestUtils.setField(svc, "id", 5L);
		Incident incident = new Incident("Orders down", null, svc, Severity.SEV1, IncidentStatus.OPEN, user, fixedTime, 15, 60);
		ReflectionTestUtils.setField(incident, "id", 2L);
		ReflectionTestUtils.setField(incident, "number", 43L);

		when(incidentRepository.findWithDetailsById(2L)).thenReturn(Optional.of(incident));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(user));
		when(timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(2L)).thenReturn(List.of());
		when(incidentDeploymentRepository.findDeploymentsByIncidentId(2L)).thenReturn(List.of());
		when(deploymentRepository.findInWindow(anyLong(), any(), any())).thenReturn(List.of());
		when(aiInvestigationClient.isEnabled()).thenReturn(true);

		when(telemetryService.queryLogs(anyLong(), any(), any(), any(), any(), anyInt()))
				.thenThrow(new ServiceUnavailableException("No telemetry"));
		when(telemetryService.queryMetric(anyLong(), any(), any(), any(), any()))
				.thenThrow(new ServiceUnavailableException("No telemetry"));

		when(aiInvestigationClient.investigate(any())).thenReturn(new AiInvestigationResult(
				true, "completed", "Summary", List.of(), List.of(), List.of(), 1, 10, 5.0
		));
		when(investigationRepository.saveAndFlush(any())).thenAnswer(inv -> {
			Investigation i = inv.getArgument(0);
			ReflectionTestUtils.setField(i, "id", 101L);
			return i;
		});

		InvestigationResponse resp = service.investigate(2L, 10L);
		assertThat(resp.summary()).isEqualTo("Summary");
	}

	@Test
	void investigateFailsWhenAiDisabled() {
		AppUser user = new AppUser("eng@test.local", "Engineer", "hash", fixedTime);
		ReflectionTestUtils.setField(user, "id", 10L);
		ServiceEntity svc = new ServiceEntity("Orders", "Orders description", user, Criticality.HIGH, fixedTime);
		ReflectionTestUtils.setField(svc, "id", 5L);
		Incident incident = new Incident("Orders down", "Desc", svc, Severity.SEV1, IncidentStatus.OPEN, user, fixedTime, 15, 60);
		ReflectionTestUtils.setField(incident, "id", 3L);
		ReflectionTestUtils.setField(incident, "number", 44L);

		when(incidentRepository.findWithDetailsById(3L)).thenReturn(Optional.of(incident));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(user));
		when(timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(3L)).thenReturn(List.of());
		when(incidentDeploymentRepository.findDeploymentsByIncidentId(3L)).thenReturn(List.of());
		when(deploymentRepository.findInWindow(anyLong(), any(), any())).thenReturn(List.of());
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
		ReflectionTestUtils.setField(actor, "id", 10L);
		ServiceEntity svc = new ServiceEntity("Orders", "Orders description", actor, Criticality.HIGH, fixedTime);
		ReflectionTestUtils.setField(svc, "id", 5L);
		Incident incident = new Incident("Orders down", "Desc", svc, Severity.SEV1, IncidentStatus.OPEN, actor, fixedTime, 15, 60);
		ReflectionTestUtils.setField(incident, "id", 1L);
		ReflectionTestUtils.setField(incident, "number", 42L);
		Investigation inv = new Investigation(incident, "Summary", "completed", 1, 10, 5.0, fixedTime);
		ReflectionTestUtils.setField(inv, "id", 1L);
		InvestigationAction action = new InvestigationAction(inv, "Restart service");
		ReflectionTestUtils.setField(action, "id", 2L);

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
		ReflectionTestUtils.setField(actor, "id", 10L);
		ServiceEntity svc = new ServiceEntity("Orders", "Orders description", actor, Criticality.HIGH, fixedTime);
		ReflectionTestUtils.setField(svc, "id", 5L);
		Incident incident = new Incident("Orders down", "Desc", svc, Severity.SEV1, IncidentStatus.OPEN, actor, fixedTime, 15, 60);
		ReflectionTestUtils.setField(incident, "id", 1L);
		ReflectionTestUtils.setField(incident, "number", 42L);
		Investigation inv = new Investigation(incident, "Summary", "completed", 1, 10, 5.0, fixedTime);
		ReflectionTestUtils.setField(inv, "id", 1L);
		InvestigationAction action = new InvestigationAction(inv, "Restart service");
		ReflectionTestUtils.setField(action, "id", 2L);

		when(investigationRepository.findById(1L)).thenReturn(Optional.of(inv));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(actor));
		when(investigationActionRepository.recordDecision(eq(2L), eq(1L), eq(ActionDecision.ACCEPTED), eq(actor), any()))
				.thenReturn(0);
		when(investigationActionRepository.findByIdAndInvestigationId(2L, 1L)).thenReturn(Optional.of(action));

		assertThatThrownBy(() -> service.decideAction(1L, 2L, ActionDecision.ACCEPTED, 10L))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("Action 2 has already been decided");
	}

	// The model's output is untrusted: only hypotheses whose every reference is evidence it was given are kept (TC-FR-18.2b).
	@Test
	void investigateKeepsOnlyHypothesesWithRealEvidenceAndFillsBlankModelOutput() {
		AppUser user = engineer();
		Incident incident = incident(user, 4L, fixedTime);
		Deployment deploy = new Deployment(incident.getService(), "v1.0.1", "abc123", "dev", fixedTime.minusSeconds(600));
		ReflectionTestUtils.setField(deploy, "id", 7L);
		when(incidentRepository.findWithDetailsById(4L)).thenReturn(Optional.of(incident));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(user));
		// An entry the system wrote has no actor.
		when(timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(4L)).thenReturn(List.of(
				new TimelineEntry(incident, null, TimelineEventType.ALERT_ATTACHED, "Alert attached", fixedTime)));
		// The deploy is linked and also falls in the window; the AI gets it once.
		when(incidentDeploymentRepository.findDeploymentsByIncidentId(4L)).thenReturn(List.of(deploy));
		when(deploymentRepository.findInWindow(anyLong(), any(), any())).thenReturn(List.of(deploy));
		when(telemetryService.queryLogs(anyLong(), any(), any(), any(), any(), anyInt())).thenReturn(List.of(
				new LogEntryResponse("log-1", fixedTime, 5L, "Checkout Service", "ERROR", "upstream timeout", Map.of())));
		when(telemetryService.queryMetric(anyLong(), any(), any(), any(), any())).thenAnswer(inv -> new MetricSeriesResponse(
				inv.getArgument(1), 5L, "Checkout Service", fixedTime.minusSeconds(3600), fixedTime, 60,
				List.of(new MetricDataPoint(fixedTime, 0.42))));
		when(aiInvestigationClient.isEnabled()).thenReturn(true);
		when(aiInvestigationClient.investigate(any())).thenReturn(new AiInvestigationResult(
				true,
				"",
				null,
				Arrays.asList(
						null,
						new AiHypothesis(null, 0.5, List.of("ev-1"), null),
						new AiHypothesis("  ", 0.5, List.of("ev-1"), null),
						new AiHypothesis("No references", 0.5, null, null),
						new AiHypothesis("Empty references", 0.5, List.of(), null),
						new AiHypothesis("Made-up evidence", 0.9, List.of("ev-1", "ev-missing"), null),
						new AiHypothesis("Bad deploy", 1.7, List.of("ev-1", "ev-2"), "Deployed 10 minutes before"),
						new AiHypothesis("Slow database", -0.2, List.of("ev-2"), null)),
				Arrays.asList(
						null,
						new AiEvidenceItem(null, "query_log_patterns", "log_pattern", "no id"),
						new AiEvidenceItem(" ", "query_log_patterns", "log_pattern", "blank id"),
						new AiEvidenceItem("ev-1", "query_deploy_events", null, null),
						new AiEvidenceItem("ev-2", "query_log_patterns", "  ", "upstream timeout x12")),
				Arrays.asList(null, "  ", " Roll back checkout "),
				3,
				120,
				8.5
		));
		when(investigationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		InvestigationResponse response = service.investigate(4L, 10L);

		ArgumentCaptor<AiInvestigationRequest> sent = ArgumentCaptor.forClass(AiInvestigationRequest.class);
		verify(aiInvestigationClient).investigate(sent.capture());
		EvidenceBundle bundle = sent.getValue().evidenceBundle();
		assertThat(bundle.timeline()).singleElement().satisfies(entry -> assertThat(entry.actor()).isNull());
		assertThat(bundle.deploys()).extracting(DeployBundleItem::id).containsExactly(7L);
		assertThat(bundle.logs()).extracting(LogBundleItem::message).containsExactly("upstream timeout");
		assertThat(bundle.metrics()).hasSize(TelemetryService.Metric.values().length)
				.allSatisfy(series -> assertThat(series.points()).extracting(MetricBundlePoint::value).containsExactly(0.42));

		assertThat(response.summary()).isEqualTo("Investigation completed.");
		assertThat(response.hypotheses())
				.extracting(InvestigationHypothesisDto::rank, InvestigationHypothesisDto::title, InvestigationHypothesisDto::confidence)
				.containsExactly(tuple(1, "Bad deploy", 1.0), tuple(2, "Slow database", 0.0));
		assertThat(response.hypotheses().getFirst().evidence())
				.extracting(InvestigationEvidenceDto::kind, InvestigationEvidenceDto::text)
				.containsExactly(tuple("evidence", ""), tuple("evidence", "upstream timeout x12"));
		assertThat(response.suggestedActions()).extracting(SuggestedActionDto::text).containsExactly("Roll back checkout");
		ArgumentCaptor<Investigation> saved = ArgumentCaptor.forClass(Investigation.class);
		verify(investigationRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo("completed");
	}

	// An incident from three days ago: telemetry is read from an hour before it began, for 24 hours, not up to now.
	@Test
	void investigateOfAnOldIncidentReadsTelemetryAroundItsStartAndStoresAnEmptyResult() {
		AppUser user = engineer();
		Instant createdAt = fixedTime.minus(Duration.ofDays(3));
		Incident incident = incident(user, 5L, createdAt);
		stubEvidence(incident, user);
		when(aiInvestigationClient.investigate(any())).thenReturn(new AiInvestigationResult(
				true, null, "  ", null, null, null, 0, 0, 0.0));
		when(investigationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		InvestigationResponse response = service.investigate(5L, 10L);

		Instant from = createdAt.minus(Duration.ofHours(1));
		Instant to = from.plus(Duration.ofHours(24));
		verify(telemetryService).queryLogs(5L, null, null, from, to, 100);
		verify(telemetryService).queryMetric(5L, "requestRate", from, to, "60s");
		assertThat(response.summary()).isEqualTo("Investigation completed.");
		assertThat(response.hypotheses()).isEmpty();
		assertThat(response.suggestedActions()).isEmpty();
		ArgumentCaptor<Investigation> saved = ArgumentCaptor.forClass(Investigation.class);
		verify(investigationRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo("completed");
	}

	@Test
	void investigateRefusesAnUnknownUserBeforeReadingTelemetryOrCallingTheAi() {
		AppUser user = engineer();
		Incident incident = incident(user, 6L, fixedTime);
		when(incidentRepository.findWithDetailsById(6L)).thenReturn(Optional.of(incident));
		when(appUserRepository.findById(10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.investigate(6L, 10L))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("User 10 not found");
		verifyNoInteractions(telemetryService, aiInvestigationClient, investigationRepository);
	}

	@Test
	void investigateSavesNothingWhenTheIncidentIsGoneAfterTheAiAnswers() {
		AppUser user = engineer();
		Incident incident = incident(user, 7L, fixedTime);
		stubEvidence(incident, user);
		when(incidentRepository.findWithDetailsById(7L)).thenReturn(Optional.of(incident), Optional.empty());
		when(aiInvestigationClient.investigate(any())).thenReturn(new AiInvestigationResult(
				true, "completed", "Summary", List.of(), List.of(), List.of(), 1, 10, 5.0));

		assertThatThrownBy(() -> service.investigate(7L, 10L))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("Incident 7 not found");
		verify(investigationRepository, never()).saveAndFlush(any());
		verify(timelineEntryRepository, never()).save(any());
	}

	@Test
	void investigateSavesNothingWhenTheUserIsGoneAfterTheAiAnswers() {
		AppUser user = engineer();
		Incident incident = incident(user, 8L, fixedTime);
		stubEvidence(incident, user);
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(user), Optional.empty());
		when(aiInvestigationClient.investigate(any())).thenReturn(new AiInvestigationResult(
				true, "completed", "Summary", List.of(), List.of(), List.of(), 1, 10, 5.0));

		assertThatThrownBy(() -> service.investigate(8L, 10L))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("User 10 not found");
		verify(investigationRepository, never()).saveAndFlush(any());
		verify(timelineEntryRepository, never()).save(any());
	}

	private AppUser engineer() {
		AppUser user = new AppUser("eng@test.local", "Engineer", "hash", fixedTime);
		ReflectionTestUtils.setField(user, "id", 10L);
		return user;
	}

	private Incident incident(AppUser user, long id, Instant createdAt) {
		ServiceEntity svc = new ServiceEntity("Checkout Service", "Checkout description", user, Criticality.HIGH, createdAt);
		ReflectionTestUtils.setField(svc, "id", 5L);
		Incident incident = new Incident("Checkout slow", "Desc", svc, Severity.SEV2, IncidentStatus.OPEN, user, createdAt, 15, 60);
		ReflectionTestUtils.setField(incident, "id", id);
		ReflectionTestUtils.setField(incident, "number", id);
		return incident;
	}

	// The incident, its user, and empty evidence, with AI switched on.
	private void stubEvidence(Incident incident, AppUser user) {
		when(incidentRepository.findWithDetailsById(incident.getId())).thenReturn(Optional.of(incident));
		when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
		when(timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId())).thenReturn(List.of());
		when(incidentDeploymentRepository.findDeploymentsByIncidentId(incident.getId())).thenReturn(List.of());
		when(deploymentRepository.findInWindow(anyLong(), any(), any())).thenReturn(List.of());
		when(telemetryService.queryLogs(anyLong(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
		when(telemetryService.queryMetric(anyLong(), any(), any(), any(), any())).thenAnswer(inv -> new MetricSeriesResponse(
				inv.getArgument(1), 5L, "Checkout Service", fixedTime.minusSeconds(3600), fixedTime, 60, List.of()));
		when(aiInvestigationClient.isEnabled()).thenReturn(true);
	}

	@Test
	void decideActionThrowsNotFoundWhenActionMissing() {
		AppUser actor = new AppUser("eng@test.local", "Engineer", "hash", fixedTime);
		ReflectionTestUtils.setField(actor, "id", 10L);
		ServiceEntity svc = new ServiceEntity("Orders", "Orders description", actor, Criticality.HIGH, fixedTime);
		ReflectionTestUtils.setField(svc, "id", 5L);
		Incident incident = new Incident("Orders down", "Desc", svc, Severity.SEV1, IncidentStatus.OPEN, actor, fixedTime, 15, 60);
		ReflectionTestUtils.setField(incident, "id", 1L);
		ReflectionTestUtils.setField(incident, "number", 42L);
		Investigation inv = new Investigation(incident, "Summary", "completed", 1, 10, 5.0, fixedTime);
		ReflectionTestUtils.setField(inv, "id", 1L);

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
