package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.dto.TriageHintServiceDto;
import com.group2.incident.incident.dto.TriageSimilarIncidentDto;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.IncidentSimilarityMatch;
import com.group2.incident.incident.repository.IncidentSimilarityRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * What the similarity service does around its queries: the text it embeds, and when it answers without asking the AI
 * or the database. The queries themselves run against pgvector in {@code IncidentSimilarityTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentSimilarityServiceTest {

	private static final long ORG = 7L;

	private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

	private static final List<Double> VECTOR = List.of(0.25, 0.5);

	@Mock
	private AiEmbeddingClient aiEmbeddingClient;

	@Mock
	private IncidentRepository incidentRepository;

	@Mock
	private IncidentSimilarityRepository similarityRepository;

	@Mock
	private ServiceRepository serviceRepository;

	private IncidentSimilarityService service;

	@BeforeEach
	void setUp() {
		OrgContext.set(ORG);
		service = new IncidentSimilarityService(aiEmbeddingClient, incidentRepository, similarityRepository, serviceRepository);
		when(aiEmbeddingClient.isEnabled()).thenReturn(true);
	}

	@AfterEach
	void clearOrg() {
		OrgContext.clear();
	}

	@Test
	void anIncidentNotEmbeddedYetHasNoSimilarIncidents() {
		Incident incident = incident();
		when(incidentRepository.findWithDetailsById(3L)).thenReturn(Optional.of(incident));
		when(similarityRepository.findEmbedding(3L, ORG)).thenReturn(Optional.empty());

		assertThat(service.findSimilarIncidents(3L)).isEmpty();
		verify(similarityRepository, never()).findMatches(anyLong(), any(), any(), any(), any(), any(), any(),
				anyBoolean(), anyDouble(), anyInt(), anyLong());
	}

	// The declare form's triage hint searches the title and symptoms together and returns each match's service.
	@Test
	void triageSearchesTheTitleAndSymptomsAndReturnsEachMatchsService() {
		when(aiEmbeddingClient.embedQuery("Title: Checkout down\nSymptoms: 502 from the gateway")).thenReturn(Optional.of(VECTOR));
		when(similarityRepository.findMatches(eq(ORG), eq("[0.25,0.5]"), isNull(), isNull(), isNull(), isNull(), isNull(),
				eq(false), anyDouble(), eq(5), eq(0L)))
				.thenReturn(List.of(match(11L, "Checkout 502s", "SEV1", "RESOLVED")));

		List<TriageSimilarIncidentDto> similar = service.findSimilarForTriage("  Checkout down ", " 502 from the gateway ");

		assertThat(similar).extracting(TriageSimilarIncidentDto::id, TriageSimilarIncidentDto::title,
				TriageSimilarIncidentDto::severity, TriageSimilarIncidentDto::service)
				.containsExactly(tuple(11L, "Checkout 502s", "SEV1", new TriageHintServiceDto(5L, "Checkout")));
	}

	@Test
	void triageWithoutSymptomsSearchesTheTitleAlone() {
		when(aiEmbeddingClient.embedQuery("Title: Checkout down")).thenReturn(Optional.empty());

		assertThat(service.findSimilarForTriage("Checkout down", "  ")).isEmpty();
		verify(aiEmbeddingClient).embedQuery("Title: Checkout down");
	}

	@Test
	void triageNeedsATitleAndTheAi() {
		assertThat(service.findSimilarForTriage(null, "Symptoms")).isEmpty();
		assertThat(service.findSimilarForTriage("  ", "Symptoms")).isEmpty();
		when(aiEmbeddingClient.isEnabled()).thenReturn(false);
		assertThat(service.findSimilarForTriage("Checkout down", null)).isEmpty();

		verify(aiEmbeddingClient, never()).embedQuery(any());
		verifyNoInteractions(similarityRepository);
	}

	@Test
	void theDuplicateCheckNeedsAKnownServiceAndATitleOfAtMost200Characters() {
		when(serviceRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.checkDuplicate("Checkout down", 99L))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("Service 99 not found");
		assertThatThrownBy(() -> service.checkDuplicate("  ", null))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("must not be blank");
		assertThatThrownBy(() -> service.checkDuplicate("x".repeat(201), null))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("must not exceed 200 characters");
		verifyNoInteractions(aiEmbeddingClient);
	}

	@Test
	void theDuplicateCheckFindsNoDuplicateWhenTheAiGivesNoVector() {
		when(aiEmbeddingClient.embedQuery("Title: " + "x".repeat(200))).thenReturn(Optional.empty());

		assertThat(service.checkDuplicate(" " + "x".repeat(200) + " ", null).duplicate()).isFalse();
		verifyNoInteractions(similarityRepository);
	}

	@Test
	void meaningSearchRefusesANegativePageAndAQueryOverTheLimit() {
		assertThatThrownBy(() -> service.searchByMeaning("checkout", null, null, null, -1))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("must not be negative");
		assertThatThrownBy(() -> service.searchByMeaning("q".repeat(32_001), null, null, null, 0))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("must not exceed 32000 characters");
		verify(aiEmbeddingClient, never()).embedQuery(any());
	}

	@Test
	void meaningSearchIsAnEmptyPageWithoutAQueryOrAVector() {
		when(aiEmbeddingClient.embedQuery("checkout latency")).thenReturn(Optional.empty());

		assertThat(service.searchByMeaning("  ", null, null, null, 2).page()).isEqualTo(2);
		assertThat(service.searchByMeaning(" checkout latency ", 5L, Severity.SEV1, IncidentStatus.OPEN, 0))
				.satisfies(page -> {
					assertThat(page.content()).isEmpty();
					assertThat(page.totalElements()).isZero();
					assertThat(page.totalPages()).isZero();
				});
		verifyNoInteractions(similarityRepository);
	}

	private static Incident incident() {
		AppUser user = new AppUser("eng@test.local", "Engineer", "hash", NOW);
		ServiceEntity checkout = new ServiceEntity("Checkout", null, user, Criticality.HIGH, NOW);
		ReflectionTestUtils.setField(checkout, "id", 5L);
		Incident incident = new Incident("Checkout down", null, checkout, Severity.SEV1, IncidentStatus.OPEN, user, NOW, 15, 60);
		ReflectionTestUtils.setField(incident, "id", 3L);
		return incident;
	}

	private static IncidentSimilarityMatch match(long id, String title, String severity, String status) {
		return new IncidentSimilarityMatch(id, id, 5L, title, "Checkout", severity, status, NOW.minusSeconds(86_400), 0.91);
	}
}
