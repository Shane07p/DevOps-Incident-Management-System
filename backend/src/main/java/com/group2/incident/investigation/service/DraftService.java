package com.group2.incident.investigation.service;

import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.followup.repository.FollowUpRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.investigation.dto.DraftKind;
import com.group2.incident.investigation.dto.DraftResponse;
import com.group2.incident.investigation.dto.PostmortemDraftDto;
import com.group2.incident.investigation.entity.ActionDecision;
import com.group2.incident.investigation.entity.Investigation;
import com.group2.incident.investigation.entity.InvestigationAction;
import com.group2.incident.investigation.repository.InvestigationRepository;
import com.group2.incident.investigation.service.AiDraftClient.AiDraftRequest;
import com.group2.incident.investigation.service.AiDraftClient.AiDraftResult;
import com.group2.incident.investigation.service.AiDraftClient.FollowUpInput;
import com.group2.incident.investigation.service.AiDraftClient.HypothesisInput;
import com.group2.incident.investigation.service.AiInvestigationClient.IncidentEvidenceContext;
import com.group2.incident.investigation.service.AiInvestigationClient.TimelineBundleItem;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class DraftService {

	private static final Pattern INC_REF = Pattern.compile("\\bINC-(\\d+)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern ENTRY_REF = Pattern.compile("\\b(?:timeline\\s+)?entry\\s*#?(\\d+)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern INCIDENT_NUM_REF = Pattern.compile("\\bincident\\s*#(\\d+)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern EMPTY_BRACKETS = Pattern.compile("\\(\\s*\\)|\\[\\s*\\]");
	private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");
	private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([.,;:])");

	private final IncidentRepository incidentRepository;
	private final TimelineEntryRepository timelineEntryRepository;
	private final InvestigationRepository investigationRepository;
	private final FollowUpRepository followUpRepository;
	private final AiDraftClient aiDraftClient;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	/**
	 * Generates an AI draft for an incident without saving anything to the database (AI-3, FR-11.4).
	 * Any incident or timeline entry references not present in the input are dropped from the response.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public DraftResponse generateDraft(Long incidentId, DraftKind kind) {
		DraftSnapshot snapshot = transactionTemplate.execute(status -> loadSnapshot(incidentId, kind));

		if (!aiDraftClient.isEnabled()) {
			throw new ServiceUnavailableException("AI drafting service is not set up on this server");
		}

		AiDraftResult result = aiDraftClient.generateDraft(snapshot.request());
		AllowedReferences allowed = snapshot.allowed();
		String sanitizedText = sanitize(result.text(), allowed);
		PostmortemDraftDto sanitizedPostmortem = kind == DraftKind.POSTMORTEM
				? sanitizePostmortem(result.postmortem(), sanitizedText, allowed)
				: null;

		return new DraftResponse(kind, sanitizedText, sanitizedPostmortem, clock.instant());
	}

	private DraftSnapshot loadSnapshot(Long incidentId, DraftKind kind) {
		Incident incident = incidentRepository.findWithDetailsById(incidentId)
				.orElseThrow(() -> new NotFoundException("Incident " + incidentId + " not found"));

		List<TimelineEntry> timeline = timelineEntryRepository
				.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId);

		Optional<Investigation> latestInv = investigationRepository
				.findTopByIncidentIdOrderByCreatedAtDescIdDesc(incidentId);

		List<HypothesisInput> hypotheses = latestInv
				.map(inv -> inv.getHypotheses().stream()
						.map(h -> new HypothesisInput(h.getRank(), h.getTitle(), h.getConfidence()))
						.toList())
				.orElse(List.of());

		List<String> acceptedActions = latestInv
				.map(inv -> inv.getSuggestedActions().stream()
						.filter(a -> a.getDecision() == ActionDecision.ACCEPTED)
						.map(InvestigationAction::getText)
						.toList())
				.orElse(List.of());

		List<FollowUpInput> followUps = followUpRepository
				.findAllByIncidentIdOrderByCreatedAtAscIdAsc(incidentId).stream()
				.map(f -> new FollowUpInput(f.getTitle(), f.getKind().name(),
						f.getOwner().getDisplayName(), f.getDueDate(), f.getStatus().name()))
				.toList();

		IncidentEvidenceContext incidentCtx = new IncidentEvidenceContext(
				incident.getId(),
				incident.getReference(),
				incident.getTitle(),
				incident.getDescription(),
				incident.getService().getName(),
				incident.getSeverity().name(),
				incident.getStatus().name(),
				incident.getCreatedAt()
		);

		List<TimelineBundleItem> timelineItems = timeline.stream()
				.map(t -> new TimelineBundleItem(
						t.getId(),
						t.getEventType().name(),
						t.getActor() != null ? t.getActor().getDisplayName() : null,
						t.getDetails(),
						t.getRecordedAt()
				))
				.toList();

		return new DraftSnapshot(
				new AiDraftRequest(kind, incidentCtx, timelineItems, hypotheses, followUps, acceptedActions),
				collectAllowedReferences(incident, timeline));
	}

	private static AllowedReferences collectAllowedReferences(Incident incident, List<TimelineEntry> timeline) {
		Set<String> incRefs = new HashSet<>();
		Set<String> incNums = new HashSet<>();
		Set<String> entryIds = new HashSet<>();

		incRefs.add(incident.getReference().toUpperCase(Locale.ROOT));
		if (incident.getId() != null) {
			incNums.add(String.valueOf(incident.getId()));
		}
		if (incident.getNumber() != null) {
			incNums.add(String.valueOf(incident.getNumber()));
		}

		collectIncRefsFromText(incident.getTitle(), incRefs, incNums);
		collectIncRefsFromText(incident.getDescription(), incRefs, incNums);

		for (TimelineEntry entry : timeline) {
			if (entry.getId() != null) {
				entryIds.add(String.valueOf(entry.getId()));
			}
			collectIncRefsFromText(entry.getDetails(), incRefs, incNums);
		}
		return new AllowedReferences(incRefs, incNums, entryIds);
	}

	private static void collectIncRefsFromText(String text, Set<String> incRefs, Set<String> incNums) {
		if (text == null || text.isBlank()) {
			return;
		}
		Matcher m = INC_REF.matcher(text);
		while (m.find()) {
			incRefs.add("INC-" + m.group(1));
			incNums.add(m.group(1));
		}
	}

	static String sanitize(String input, AllowedReferences allowed) {
		if (input == null || input.isBlank()) {
			return "";
		}
		String out = replaceMatches(input, INC_REF, m -> {
			String norm = "INC-" + m.group(1);
			return allowed.incidentRefs().contains(norm) ? m.group(0) : "";
		});
		out = replaceMatches(out, INCIDENT_NUM_REF,
				m -> allowed.incidentNumbers().contains(m.group(1)) ? m.group(0) : "");
		out = replaceMatches(out, ENTRY_REF,
				m -> allowed.entryIds().contains(m.group(1)) ? m.group(0) : "");
		out = EMPTY_BRACKETS.matcher(out).replaceAll("");
		out = MULTI_SPACE.matcher(out).replaceAll(" ");
		out = SPACE_BEFORE_PUNCT.matcher(out).replaceAll("$1");
		return out.trim();
	}

	private static PostmortemDraftDto sanitizePostmortem(
			PostmortemDraftDto pm,
			String fallbackSummary,
			AllowedReferences allowed
	) {
		if (pm == null) {
			return new PostmortemDraftDto(fallbackSummary, "", "", "", "", "");
		}
		return new PostmortemDraftDto(
				sanitize(pm.summary(), allowed),
				sanitize(pm.impact(), allowed),
				sanitize(pm.rootCause(), allowed),
				sanitize(pm.resolution(), allowed),
				sanitize(pm.wentWell(), allowed),
				sanitize(pm.wentBadly(), allowed)
		);
	}

	private static String replaceMatches(String input, Pattern pattern, java.util.function.Function<Matcher, String> fn) {
		Matcher matcher = pattern.matcher(input);
		StringBuilder sb = new StringBuilder();
		while (matcher.find()) {
			matcher.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(matcher)));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	private record DraftSnapshot(AiDraftRequest request, AllowedReferences allowed) {
	}

	record AllowedReferences(Set<String> incidentRefs, Set<String> incidentNumbers, Set<String> entryIds) {
	}
}
