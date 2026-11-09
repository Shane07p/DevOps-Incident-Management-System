package com.group2.incident.incident.service;

import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.dto.DuplicateCheckResponse;
import com.group2.incident.incident.dto.DuplicateIncidentResponse;
import com.group2.incident.incident.dto.IncidentSearchPageResponse;
import com.group2.incident.incident.dto.IncidentSearchResult;
import com.group2.incident.incident.dto.SimilarIncidentResponse;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.IncidentSimilarityMatch;
import com.group2.incident.incident.repository.IncidentSimilarityRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncidentSimilarityService {

	private static final double SIMILARITY_THRESHOLD = 0.70;

	private static final int SEARCH_PAGE_SIZE = 20;

	private static final int MAX_EMBEDDING_QUERY_LENGTH = 32_000;

	private final AiEmbeddingClient aiEmbeddingClient;

	private final IncidentRepository incidentRepository;

	private final IncidentSimilarityRepository similarityRepository;

	private final ServiceRepository serviceRepository;

	@Transactional(readOnly = true)
	public List<SimilarIncidentResponse> findSimilarIncidents(long incidentId) {
		Incident incident = incidentRepository.findWithDetailsById(incidentId)
				.orElseThrow(() -> new NotFoundException("Incident " + incidentId + " not found"));
		if (!aiEmbeddingClient.isEnabled()) {
			return List.of();
		}

		long orgId = OrgContext.current();
		Optional<String> embedding = similarityRepository.findEmbedding(incidentId, orgId);
		if (embedding.isEmpty()) {
			return List.of();
		}

		return similarityRepository.findMatches(
						orgId, embedding.get(), incidentId, null, null, null,
						false, SIMILARITY_THRESHOLD, 3, 0)
				.stream()
				.map(IncidentSimilarityService::toSimilarIncident)
				.toList();
	}

	@Transactional(readOnly = true)
	public DuplicateCheckResponse checkDuplicate(String title, Long serviceId) {
		String normalizedTitle = validateTitle(title);
		String serviceName = null;
		if (serviceId != null) {
			ServiceEntity service = serviceRepository.findById(serviceId)
					.orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));
			serviceName = service.getName();
		}
		if (!aiEmbeddingClient.isEnabled()) {
			return noDuplicate();
		}

		String queryText = serviceName == null
				? "Title: " + normalizedTitle
				: "Title: " + normalizedTitle + "\nService: " + serviceName;
		Optional<List<Double>> embedding = aiEmbeddingClient.embedQuery(queryText);
		if (embedding.isEmpty()) {
			return noDuplicate();
		}

		long orgId = OrgContext.current();
		List<IncidentSimilarityMatch> matches = similarityRepository.findMatches(
				orgId, vectorLiteral(embedding.get()), null, serviceId, null, null, true,
				SIMILARITY_THRESHOLD, 1, 0);
		if (matches.isEmpty()) {
			return noDuplicate();
		}

		IncidentSimilarityMatch match = matches.getFirst();
		return new DuplicateCheckResponse(
				true,
				new DuplicateIncidentResponse(
						match.id(),
						Incident.reference(match.number()),
						match.title(),
						IncidentStatus.valueOf(match.status()),
						match.score()),
				"Potential duplicate detected");
	}

	@Transactional(readOnly = true)
	public IncidentSearchPageResponse searchByMeaning(
			String query,
			Long serviceId,
			Severity severity,
			IncidentStatus status,
			int page
	) {
		if (page < 0) {
			throw new FieldValidationException("page", "must not be negative");
		}
		if (query == null || query.isBlank() || !aiEmbeddingClient.isEnabled()) {
			return emptySearchPage(page);
		}
		String normalizedQuery = query.trim();
		if (normalizedQuery.length() > MAX_EMBEDDING_QUERY_LENGTH) {
			throw new FieldValidationException("q", "must not exceed 32000 characters");
		}

		Optional<List<Double>> embedding = aiEmbeddingClient.embedQuery(normalizedQuery);
		if (embedding.isEmpty()) {
			return emptySearchPage(page);
		}

		long orgId = OrgContext.current();
		String vector = vectorLiteral(embedding.get());
		Long totalElements = similarityRepository.countMatches(
				orgId, vector, null, serviceId,
				severity != null ? severity.name() : null,
				status != null ? status.name() : null,
				false, SIMILARITY_THRESHOLD);
		List<IncidentSearchResult> content = similarityRepository.findMatches(
						orgId, vector, null, serviceId,
						severity != null ? severity.name() : null,
						status != null ? status.name() : null,
						false, SIMILARITY_THRESHOLD,
						SEARCH_PAGE_SIZE, (long) page * SEARCH_PAGE_SIZE)
				.stream()
				.map(IncidentSimilarityService::toSearchResult)
				.toList();

		return new IncidentSearchPageResponse(
				content,
				page,
				SEARCH_PAGE_SIZE,
				totalElements,
				totalPages(totalElements));
	}

	private static String validateTitle(String title) {
		if (title == null || title.isBlank()) {
			throw new FieldValidationException("title", "must not be blank");
		}
		String normalizedTitle = title.trim();
		if (normalizedTitle.length() > 200) {
			throw new FieldValidationException("title", "must not exceed 200 characters");
		}
		return normalizedTitle;
	}

	private static String vectorLiteral(List<Double> embedding) {
		return embedding.stream()
				.map(value -> Double.toString(value))
				.collect(Collectors.joining(",", "[", "]"));
	}

	private static SimilarIncidentResponse toSimilarIncident(IncidentSimilarityMatch match) {
		return new SimilarIncidentResponse(
				match.id(),
				Incident.reference(match.number()),
				match.title(),
				match.serviceName(),
				Severity.valueOf(match.severity()),
				IncidentStatus.valueOf(match.status()),
				match.score(),
				null);
	}

	private static IncidentSearchResult toSearchResult(IncidentSimilarityMatch match) {
		return new IncidentSearchResult(
				match.id(),
				Incident.reference(match.number()),
				match.serviceId(),
				match.title(),
				match.serviceName(),
				Severity.valueOf(match.severity()),
				IncidentStatus.valueOf(match.status()),
				match.createdAt(),
				List.of());
	}

	private static IncidentSearchPageResponse emptySearchPage(int page) {
		return new IncidentSearchPageResponse(List.of(), page, SEARCH_PAGE_SIZE, 0, 0);
	}

	private static int totalPages(long totalElements) {
		return totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / SEARCH_PAGE_SIZE);
	}

	private static DuplicateCheckResponse noDuplicate() {
		return new DuplicateCheckResponse(false, null, null);
	}
}
