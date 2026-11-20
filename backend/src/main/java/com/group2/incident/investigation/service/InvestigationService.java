package com.group2.incident.investigation.service;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.deploy.entity.Deployment;
import com.group2.incident.deploy.repository.DeploymentRepository;
import com.group2.incident.deploy.repository.IncidentDeploymentRepository;
import com.group2.incident.incident.entity.Incident;
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
import com.group2.incident.investigation.entity.InvestigationEvidence;
import com.group2.incident.investigation.entity.InvestigationHypothesis;
import com.group2.incident.investigation.repository.InvestigationActionRepository;
import com.group2.incident.investigation.repository.InvestigationRepository;
import com.group2.incident.investigation.service.AiInvestigationClient.AiEvidenceItem;
import com.group2.incident.investigation.service.AiInvestigationClient.AiHypothesis;
import com.group2.incident.investigation.service.AiInvestigationClient.AiInvestigationRequest;
import com.group2.incident.investigation.service.AiInvestigationClient.AiInvestigationResult;
import com.group2.incident.investigation.service.AiInvestigationClient.DeployBundleItem;
import com.group2.incident.investigation.service.AiInvestigationClient.EvidenceBundle;
import com.group2.incident.investigation.service.AiInvestigationClient.IncidentEvidenceContext;
import com.group2.incident.investigation.service.AiInvestigationClient.LogBundleItem;
import com.group2.incident.investigation.service.AiInvestigationClient.MetricBundlePoint;
import com.group2.incident.investigation.service.AiInvestigationClient.MetricBundleSeries;
import com.group2.incident.investigation.service.AiInvestigationClient.TimelineBundleItem;
import com.group2.incident.telemetry.dto.LogEntryResponse;
import com.group2.incident.telemetry.dto.MetricSeriesResponse;
import com.group2.incident.telemetry.service.TelemetryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvestigationService {

	private static final Duration TELEMETRY_MAX_RANGE = Duration.ofHours(24);
	private static final Duration TELEMETRY_LOOKBACK = Duration.ofHours(1);
	private static final Duration DEPLOY_LOOKBACK = Duration.ofHours(2);
	private static final int LOG_LIMIT = 100;
	private static final int MAX_DESCRIPTION_LENGTH = 5000;
	private static final int MAX_HYPOTHESIS_TITLE_LENGTH = 500;
	private static final int MAX_EVIDENCE_KIND_LENGTH = 100;

	private final IncidentRepository incidentRepository;
	private final TimelineEntryRepository timelineEntryRepository;
	private final DeploymentRepository deploymentRepository;
	private final IncidentDeploymentRepository incidentDeploymentRepository;
	private final TelemetryService telemetryService;
	private final AiInvestigationClient aiInvestigationClient;
	private final InvestigationRepository investigationRepository;
	private final InvestigationActionRepository investigationActionRepository;
	private final AppUserRepository appUserRepository;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	/**
	 * Gathers incident evidence (timeline, recent deploys, logs, metrics), runs an AI investigation,
	 * and stores the verified result. If AI is unset, down, or slow, throws 503 without mutating the incident.
	 *
	 * Holds no database transaction while calling telemetry or ai-service.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public InvestigationResponse investigate(Long incidentId, Long actorId) {
		// 1. Gather incident data, timeline, and deploys in a short read transaction
		IncidentSnapshot snapshot = transactionTemplate.execute(status -> {
			Incident incident = incidentRepository.findWithDetailsById(incidentId)
					.orElseThrow(() -> new NotFoundException("Incident " + incidentId + " not found"));
			appUserRepository.findById(actorId)
					.orElseThrow(() -> new NotFoundException("User " + actorId + " not found"));

			List<TimelineEntry> timeline = timelineEntryRepository
					.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
			List<TimelineBundleItem> timelineItems = timeline.stream()
					.map(t -> new TimelineBundleItem(
							t.getId(),
							t.getEventType().name(),
							t.getActor() != null ? t.getActor().getDisplayName() : null,
							t.getDetails(),
							t.getRecordedAt()
					))
					.toList();

			List<Deployment> deploys = gatherDeploys(incident);
			List<DeployBundleItem> deployItems = deploys.stream()
					.map(d -> new DeployBundleItem(
							d.getId(),
							d.getService().getName(),
							d.getVersion(),
							d.getCommit(),
							d.getAuthor(),
							d.getDeployedAt()
					))
					.toList();

			return new IncidentSnapshot(
					incident.getId(),
					incident.getReference(),
					incident.getTitle(),
					incident.getDescription(),
					incident.getService().getName(),
					incident.getService().getId(),
					incident.getSeverity().name(),
					incident.getStatus().name(),
					incident.getCreatedAt(),
					timelineItems,
					deployItems
			);
		});

		if (!aiInvestigationClient.isEnabled()) {
			throw new ServiceUnavailableException("AI investigation service is not set up on this server");
		}

		// 2. Telemetry window: starts at createdAt minus 1h and ends at min(now, start plus 24h)
		Instant now = clock.instant();
		Instant windowStart = snapshot.createdAt().minus(TELEMETRY_LOOKBACK);
		Instant maxEnd = windowStart.plus(TELEMETRY_MAX_RANGE);
		Instant windowEnd = now.isBefore(maxEnd) ? now : maxEnd;

		// Gather telemetry logs and metrics outside DB transaction
		List<LogBundleItem> logItems = gatherLogs(snapshot.serviceId(), windowStart, windowEnd);
		List<MetricBundleSeries> metricSeries = gatherMetrics(snapshot.serviceId(), windowStart, windowEnd);

		// Truncate description to 5000 characters for ai-service
		String description = snapshot.description();
		if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
			description = description.substring(0, MAX_DESCRIPTION_LENGTH);
		}

		String serviceLabel = snapshot.serviceName().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
		IncidentEvidenceContext incidentContext = new IncidentEvidenceContext(
				snapshot.incidentId(),
				snapshot.reference(),
				snapshot.title(),
				description,
				snapshot.serviceName(),
				snapshot.severity(),
				snapshot.status(),
				snapshot.createdAt()
		);

		EvidenceBundle bundle = new EvidenceBundle(
				incidentContext,
				snapshot.timelineItems(),
				snapshot.deployItems(),
				logItems,
				metricSeries
		);
		AiInvestigationRequest request = new AiInvestigationRequest(
				serviceLabel,
				snapshot.createdAt().toString(),
				null,
				description,
				bundle
		);

		// 3. Call AI service outside DB transaction
		AiInvestigationResult result = aiInvestigationClient.investigate(request);

		// 4. Save result in a short write transaction
		return transactionTemplate.execute(status -> {
			Incident incident = incidentRepository.findWithDetailsById(incidentId)
					.orElseThrow(() -> new NotFoundException("Incident " + incidentId + " not found"));
			AppUser actor = appUserRepository.findById(actorId)
					.orElseThrow(() -> new NotFoundException("User " + actorId + " not found"));

			Instant recordedAt = clock.instant();
			String summary = result.summary() != null && !result.summary().isBlank()
					? result.summary()
					: "Investigation completed.";
			String invStatus = result.status() != null && !result.status().isBlank()
					? result.status()
					: "completed";

			Investigation investigation = new Investigation(
					incident,
					summary,
					invStatus,
					result.stepsTaken(),
					result.tokensUsed(),
					result.durationMs(),
					recordedAt
			);

			Map<String, AiEvidenceItem> evidenceById = new LinkedHashMap<>();
			if (result.evidence() != null) {
				for (AiEvidenceItem item : result.evidence()) {
					if (item != null && item.id() != null && !item.id().isBlank()) {
						evidenceById.put(item.id(), item);
					}
				}
			}

			int rank = 1;
			if (result.hypotheses() != null) {
				for (AiHypothesis hyp : result.hypotheses()) {
					if (hyp == null || hyp.hypothesis() == null || hyp.hypothesis().isBlank()
							|| hyp.evidenceRefs() == null || hyp.evidenceRefs().isEmpty()) {
						continue;
					}
					List<AiEvidenceItem> citedItems = new ArrayList<>();
					boolean allValid = true;
					for (String refId : hyp.evidenceRefs()) {
						AiEvidenceItem item = evidenceById.get(refId);
						if (item == null) {
							allValid = false;
							break;
						}
						citedItems.add(item);
					}
					if (!allValid || citedItems.isEmpty()) {
						continue;
					}

					String title = hyp.hypothesis();
					if (title != null && title.length() > MAX_HYPOTHESIS_TITLE_LENGTH) {
						title = title.substring(0, MAX_HYPOTHESIS_TITLE_LENGTH);
					}

					InvestigationHypothesis entityHyp = new InvestigationHypothesis(
							investigation,
							rank++,
							title,
							Math.max(0.0, Math.min(1.0, hyp.confidence())),
							hyp.reasoning()
					);
					for (AiEvidenceItem ev : citedItems) {
						String kind = ev.evidenceType() != null && !ev.evidenceType().isBlank()
								? ev.evidenceType()
								: "evidence";
						if (kind.length() > MAX_EVIDENCE_KIND_LENGTH) {
							kind = kind.substring(0, MAX_EVIDENCE_KIND_LENGTH);
						}
						String text = ev.content() != null ? ev.content() : "";
						entityHyp.addEvidence(new InvestigationEvidence(entityHyp, kind, text));
					}
					investigation.addHypothesis(entityHyp);
				}
			}

			if (result.suggestedActions() != null) {
				for (String act : result.suggestedActions()) {
					if (act != null && !act.isBlank()) {
						investigation.addAction(new InvestigationAction(investigation, act.trim()));
					}
				}
			}

			Investigation saved = investigationRepository.saveAndFlush(investigation);

			timelineEntryRepository.save(new TimelineEntry(
					incident,
					actor,
					TimelineEventType.INVESTIGATION_COMPLETED,
					"AI investigation completed with "
							+ saved.getHypotheses().size() + " hypothesis(es)",
					recordedAt
			));

			return toResponse(saved);
		});
	}

	@Transactional(readOnly = true)
	public InvestigationResponse getLatest(Long incidentId) {
		if (!incidentRepository.existsById(incidentId)) {
			throw new NotFoundException("Incident " + incidentId + " not found");
		}
		Investigation investigation = investigationRepository.findTopByIncidentIdOrderByCreatedAtDescIdDesc(incidentId)
				.orElseThrow(() -> new NotFoundException("No investigation found for incident " + incidentId));
		return toResponse(investigation);
	}

	/**
	 * Records an Engineer/Admin Accept or Reject decision on a suggested action and appends a timeline entry.
	 * Nothing is executed (FR-19.3).
	 * Fails with 409 Conflict if the action has already been decided.
	 */
	@Transactional
	public SuggestedActionDto decideAction(Long investigationId, Long actionId, ActionDecision decision, Long actorId) {
		Investigation investigation = investigationRepository.findById(investigationId)
				.orElseThrow(() -> new NotFoundException("Investigation " + investigationId + " not found"));
		AppUser actor = appUserRepository.findById(actorId)
				.orElseThrow(() -> new NotFoundException("User " + actorId + " not found"));

		Instant now = clock.instant();
		int updated = investigationActionRepository.recordDecision(actionId, investigationId, decision, actor, now);
		if (updated == 0) {
			investigationActionRepository.findByIdAndInvestigationId(actionId, investigationId)
					.orElseThrow(() -> new NotFoundException(
							"Action " + actionId + " not found for investigation " + investigationId));
			throw new ConflictException("Action " + actionId + " has already been decided");
		}

		InvestigationAction action = investigationActionRepository.findByIdAndInvestigationId(actionId, investigationId)
				.orElseThrow(() -> new NotFoundException(
						"Action " + actionId + " not found for investigation " + investigationId));

		TimelineEventType eventType = decision == ActionDecision.ACCEPTED
				? TimelineEventType.ACTION_ACCEPTED
				: TimelineEventType.ACTION_REJECTED;
		String verb = decision == ActionDecision.ACCEPTED ? "Accepted" : "Rejected";
		timelineEntryRepository.save(new TimelineEntry(
				investigation.getIncident(),
				actor,
				eventType,
				verb + " AI suggested action: " + action.getText(),
				now
		));

		return toActionDto(action);
	}

	private List<Deployment> gatherDeploys(Incident incident) {
		List<Deployment> linked = incidentDeploymentRepository.findDeploymentsByIncidentId(incident.getId());
		Instant windowStart = incident.getCreatedAt().minus(DEPLOY_LOOKBACK);
		Instant windowEnd = incident.getCreatedAt();
		List<Deployment> candidate = deploymentRepository.findInWindow(incident.getService().getId(), windowStart, windowEnd);

		Map<Long, Deployment> combined = new LinkedHashMap<>();
		for (Deployment d : linked) {
			combined.put(d.getId(), d);
		}
		for (Deployment d : candidate) {
			combined.putIfAbsent(d.getId(), d);
		}
		return combined.values().stream()
				.sorted(Comparator.comparing(Deployment::getDeployedAt, Comparator.reverseOrder())
						.thenComparing(Deployment::getId, Comparator.reverseOrder()))
				.toList();
	}

	private List<LogBundleItem> gatherLogs(long serviceId, Instant from, Instant to) {
		try {
			List<LogEntryResponse> logs = telemetryService.queryLogs(serviceId, null, null, from, to, LOG_LIMIT);
			return logs.stream()
					.map(l -> new LogBundleItem(l.id(), l.timestamp(), l.level(), l.message(), l.attributes()))
					.toList();
		}
		catch (ServiceUnavailableException ex) {
			log.debug("Telemetry logs unavailable during investigation for service {}: {}", serviceId, ex.getMessage());
			return List.of();
		}
	}

	private List<MetricBundleSeries> gatherMetrics(long serviceId, Instant from, Instant to) {
		List<MetricBundleSeries> series = new ArrayList<>();
		for (TelemetryService.Metric metric : TelemetryService.Metric.values()) {
			try {
				MetricSeriesResponse response = telemetryService.queryMetric(serviceId, metric.apiName(), from, to, "60s");
				List<MetricBundlePoint> points = response.points().stream()
						.map(p -> new MetricBundlePoint(p.timestamp(), p.value()))
						.toList();
				series.add(new MetricBundleSeries(response.metric(), response.serviceName(), points));
			}
			catch (ServiceUnavailableException ex) {
				log.debug("Telemetry metric {} unavailable during investigation for service {}: {}",
						metric.apiName(), serviceId, ex.getMessage());
				break;
			}
		}
		return series;
	}

	private static InvestigationResponse toResponse(Investigation investigation) {
		List<InvestigationHypothesisDto> hypotheses = investigation.getHypotheses().stream()
				.map(h -> new InvestigationHypothesisDto(
						h.getRank(),
						h.getTitle(),
						h.getConfidence(),
						h.getEvidence().stream()
								.map(e -> new InvestigationEvidenceDto(e.getKind(), e.getText()))
								.toList()
				))
				.toList();

		List<SuggestedActionDto> actions = investigation.getSuggestedActions().stream()
				.map(InvestigationService::toActionDto)
				.toList();

		return new InvestigationResponse(
				investigation.getId(),
				investigation.getSummary(),
				hypotheses,
				actions,
				investigation.getCreatedAt()
		);
	}

	private static SuggestedActionDto toActionDto(InvestigationAction action) {
		return new SuggestedActionDto(
				action.getId(),
				action.getText(),
				action.getDecision(),
				action.getDecidedBy() != null ? action.getDecidedBy().getDisplayName() : null,
				action.getDecidedAt()
		);
	}

	private record IncidentSnapshot(
			long incidentId,
			String reference,
			String title,
			String description,
			String serviceName,
			long serviceId,
			String severity,
			String status,
			Instant createdAt,
			List<TimelineBundleItem> timelineItems,
			List<DeployBundleItem> deployItems
	) {}
}
