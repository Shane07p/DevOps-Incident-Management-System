package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.service.AiEmbeddingClient;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class IncidentSimilarityTest {

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ServiceRepository serviceRepository;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	@MockitoBean
	private AiEmbeddingClient aiEmbeddingClient;

	private AppUser engineer;

	private ServiceEntity payments;

	private ServiceEntity identity;

	private String token;

	private long nextIncidentNumber;

	@BeforeEach
	void setUp() throws Exception {
		engineer = accounts.create("similarity-engineer@test.local", Role.ENGINEER);
		token = accounts.token("similarity-engineer@test.local");
		payments = createService("SimilarityPayments");
		identity = createService("SimilarityIdentity");
		nextIncidentNumber = clock.millis() * 1000;
	}

	@Test
	@DisplayName("Similar incidents are organization-scoped, above threshold, score ordered, and capped at three")
	void similarIncidentsAreThresholdedOrderedAndCapped() throws Exception {
		Incident source = createIncident("Current outage", payments, Severity.SEV1, IncidentStatus.OPEN);
		Incident exact = createIncident("Exact match", payments, Severity.SEV2, IncidentStatus.RESOLVED);
		Incident pointNine = createIncident("90 percent match", payments, Severity.SEV2, IncidentStatus.CLOSED);
		Incident pointEight = createIncident("80 percent match", payments, Severity.SEV3, IncidentStatus.INVESTIGATING);
		Incident pointSevenFive = createIncident("75 percent match", payments, Severity.SEV3, IncidentStatus.OPEN);
		Incident belowThreshold = createIncident("Below threshold", payments, Severity.SEV4, IncidentStatus.OPEN);
		Incident newer = createIncident("Newer open lookalike", payments, Severity.SEV1, IncidentStatus.OPEN);
		setCreatedAtOffset(exact, -60);
		setCreatedAtOffset(pointNine, -60);
		setCreatedAtOffset(pointEight, -60);
		setCreatedAtOffset(pointSevenFive, -60);
		setCreatedAtOffset(belowThreshold, -60);
		setCreatedAtOffset(newer, 60);
		setEmbedding(source, vector(1, 0));
		setEmbedding(exact, vector(1, 0));
		setEmbedding(pointNine, vector(0.9, Math.sqrt(0.19)));
		setEmbedding(pointEight, vector(0.8, 0.6));
		setEmbedding(pointSevenFive, vector(0.75, Math.sqrt(0.4375)));
		setEmbedding(belowThreshold, vector(0.69, Math.sqrt(1 - 0.69 * 0.69)));
		setEmbedding(newer, vector(1, 0));
		when(aiEmbeddingClient.isEnabled()).thenReturn(true);

		mvc.perform(get("/api/incidents/{id}/similar", source.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].id").value(exact.getId().intValue()))
				.andExpect(jsonPath("$[0].reference").value(exact.getReference()))
				.andExpect(jsonPath("$[0].serviceName").value("SimilarityPayments"))
				.andExpect(jsonPath("$[0].score").value(closeTo(1.0, 0.001)))
				.andExpect(jsonPath("$[0].fix").doesNotExist())
				.andExpect(jsonPath("$[1].id").value(pointNine.getId().intValue()))
				.andExpect(jsonPath("$[1].score").value(closeTo(0.9, 0.002)))
				.andExpect(jsonPath("$[2].id").value(pointEight.getId().intValue()))
				.andExpect(jsonPath("$[2].score").value(closeTo(0.8, 0.002)));
	}

	@Test
	@DisplayName("The semantic history mode embeds a query and retains filters and pagination")
	void semanticSearchAppliesFiltersAndPagination() throws Exception {
		List<Incident> matching = new ArrayList<>();
		for (int index = 0; index < 21; index++) {
			Incident incident = createIncident(
					"Meaning result " + index, payments, Severity.SEV1, IncidentStatus.RESOLVED);
			setEmbedding(incident, vector(1, 0));
			matching.add(incident);
		}
		Incident wrongService = createIncident("Wrong service", identity, Severity.SEV1, IncidentStatus.RESOLVED);
		Incident wrongSeverity = createIncident("Wrong severity", payments, Severity.SEV2, IncidentStatus.RESOLVED);
		Incident wrongStatus = createIncident("Wrong status", payments, Severity.SEV1, IncidentStatus.OPEN);
		setEmbedding(wrongService, vector(1, 0));
		setEmbedding(wrongSeverity, vector(1, 0));
		setEmbedding(wrongStatus, vector(1, 0));
		when(aiEmbeddingClient.isEnabled()).thenReturn(true);
		when(aiEmbeddingClient.embedQuery("checkout latency")).thenReturn(Optional.of(vector(1, 0)));

		mvc.perform(get("/api/incidents/search")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.queryParam("q", "checkout latency")
						.queryParam("mode", "semantic")
						.queryParam("serviceId", payments.getId().toString())
						.queryParam("severity", Severity.SEV1.name())
						.queryParam("status", IncidentStatus.RESOLVED.name())
						.queryParam("page", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].id").value(matching.getFirst().getId().intValue()))
				.andExpect(jsonPath("$.content[0].matchedIn", hasSize(0)))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(21))
				.andExpect(jsonPath("$.totalPages").value(2));
		verify(aiEmbeddingClient).embedQuery("checkout latency");
	}

	@Test
	@DisplayName("A blank semantic query uses the filtered keyword search without calling AI")
	void blankSemanticQueryFallsBackToFilteredKeywordSearch() throws Exception {
		Incident matching = createIncident("Filtered history result", payments, Severity.SEV1, IncidentStatus.RESOLVED);
		createIncident("Wrong severity history result", payments, Severity.SEV2, IncidentStatus.RESOLVED);
		createIncident("Wrong status history result", payments, Severity.SEV1, IncidentStatus.OPEN);
		when(aiEmbeddingClient.isEnabled()).thenReturn(true);

		mvc.perform(get("/api/incidents/search")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.queryParam("q", " ")
						.queryParam("mode", "semantic")
						.queryParam("serviceId", payments.getId().toString())
						.queryParam("severity", Severity.SEV1.name())
						.queryParam("status", IncidentStatus.RESOLVED.name()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].id").value(matching.getId().intValue()))
				.andExpect(jsonPath("$.totalElements").value(1));

		verify(aiEmbeddingClient, never()).embedQuery(anyString());
	}

	@Test
	@DisplayName("Duplicate check returns the closest open match and never uses resolved incidents")
	void duplicateCheckReturnsOnlyOpenMatches() throws Exception {
		Incident open = createIncident("Open checkout outage", payments, Severity.SEV1, IncidentStatus.INVESTIGATING);
		Incident resolved = createIncident("Resolved checkout outage", payments, Severity.SEV1, IncidentStatus.RESOLVED);
		setEmbedding(open, vector(1, 0));
		setEmbedding(resolved, vector(1, 0));
		when(aiEmbeddingClient.isEnabled()).thenReturn(true);
		when(aiEmbeddingClient.embedQuery("Title: Checkout timeout\nService: SimilarityPayments"))
				.thenReturn(Optional.of(vector(1, 0)));
		long countBefore = incidentRepository.count();

		mvc.perform(get("/api/incidents/duplicate-check")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.queryParam("title", "Checkout timeout")
						.queryParam("serviceId", payments.getId().toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.duplicate").value(true))
				.andExpect(jsonPath("$.incident.id").value(open.getId().intValue()))
				.andExpect(jsonPath("$.incident.reference").value(open.getReference()))
				.andExpect(jsonPath("$.incident.status").value("INVESTIGATING"))
				.andExpect(jsonPath("$.incident.score").value(closeTo(1.0, 0.001)))
				.andExpect(jsonPath("$.message").value("Potential duplicate detected"));

		assertThat(incidentRepository.count()).isEqualTo(countBefore);
	}

	@Test
	@DisplayName("AI-off lookup returns the documented empty shapes")
	void aiOffReturnsEmptySimilarSearchAndDuplicateResults() throws Exception {
		Incident source = createIncident("AI off source", payments, Severity.SEV2, IncidentStatus.OPEN);
		setEmbedding(source, vector(1, 0));

		mvc.perform(get("/api/incidents/{id}/similar", source.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));

		mvc.perform(get("/api/incidents/duplicate-check")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.queryParam("title", "AI off source"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.duplicate").value(false))
				.andExpect(jsonPath("$.incident").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.message").doesNotExist());

		mvc.perform(get("/api/incidents/search")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.queryParam("q", "AI off source")
						.queryParam("mode", "semantic"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(0)))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	@DisplayName("An unknown history search mode is rejected instead of silently falling back")
	void unknownSearchModeIsRejected() throws Exception {
		mvc.perform(get("/api/incidents/search")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.queryParam("q", "checkout")
						.queryParam("mode", "approximate"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.mode").value("must be keyword or semantic"));
	}

	private ServiceEntity createService(String name) {
		ServiceEntity service = new ServiceEntity(name, "Similarity test service", engineer,
				Criticality.HIGH, clock.instant());
		for (Severity severity : Severity.values()) {
			service.putTarget(severity, 15, 240);
		}
		return serviceRepository.saveAndFlush(service);
	}

	private Incident createIncident(String title, ServiceEntity service, Severity severity, IncidentStatus status) {
		Incident incident = new Incident(
				title, "Stored incident description", service, severity, status, engineer, clock.instant(), 15, 240);
		incident.assignNumber(nextIncidentNumber++);
		return incidentRepository.saveAndFlush(incident);
	}

	private Incident findByTitle(String title) {
		return incidentRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
				.filter(incident -> incident.getTitle().equals(title))
				.findFirst()
				.orElseThrow();
	}

	private void setEmbedding(Incident incident, List<Double> vector) {
		String literal = vector.stream()
				.map(value -> Double.toString(value))
				.collect(java.util.stream.Collectors.joining(",", "[", "]"));
		int updated = jdbcTemplate.update("""
				update incident
				set embedding = cast(? as incident.vector), embedding_retry_at = null
				where id = ? and org_id = ?
				""", literal, incident.getId(), OrgContext.current());
		assertThat(updated).isEqualTo(1);
	}

	private void setCreatedAtOffset(Incident incident, int seconds) {
		int updated = jdbcTemplate.update(
				"update incident set created_at = created_at + (? * interval '1 second') where id = ? and org_id = ?",
				seconds, incident.getId(), OrgContext.current());
		assertThat(updated).isEqualTo(1);
	}

	private static List<Double> vector(double x, double y) {
		List<Double> values = new ArrayList<>(768);
		values.add(x);
		values.add(y);
		for (int index = 2; index < 768; index++) {
			values.add(0.0);
		}
		return values;
	}
}
