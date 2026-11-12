package com.group2.incident.postmortem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.postmortem.dto.PostmortemRequest;
import com.group2.incident.postmortem.entity.PostmortemStatus;
import com.group2.incident.postmortem.repository.PostmortemRepository;
import com.group2.incident.postmortem.service.PostmortemService;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
class PostmortemTest {

	private static final String STALE = "This record was changed by another request; reload and try again";
	private static final String CLOSURE = "A reviewed postmortem is required to close a SEV1 or SEV2 incident";

	@Autowired private MockMvc mvc;
	@Autowired private TestAccounts accounts;
	@Autowired private ServiceRepository services;
	@Autowired private IncidentRepository incidents;
	@Autowired private PostmortemRepository postmortems;
	@Autowired private TimelineEntryRepository timeline;
	@Autowired private PostmortemService service;
	@Autowired private EntityManager entityManager;
	@Autowired private ObjectMapper json;
	@Autowired private Clock clock;

	private AppUser author;
	private AppUser reviewer;
	private String authorToken;
	private String reviewerToken;
	private ServiceEntity payments;
	private Incident incident;
	private long nextNumber;

	@BeforeEach
	void setUp() throws Exception {
		author = accounts.create("pm-author@test.local", Role.ENGINEER);
		reviewer = accounts.create("pm-reviewer@test.local", Role.ADMIN);
		authorToken = accounts.token(author.getEmail());
		reviewerToken = accounts.token(reviewer.getEmail());
		payments = services.saveAndFlush(new ServiceEntity("Postmortem payments", "Payments", author,
				Criticality.HIGH, clock.instant()));
		nextNumber = 1;
		incident = createIncident(Severity.SEV1);
	}

	@Test
	void saveReloadsEveryFieldAndNamesTheLastEditor() throws Exception {
		String created = save(authorToken, request(null)).andExpect(status().isOk())
				.andExpect(jsonPath("$.incidentId").value(incident.getId()))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.author.id").value(author.getId()))
				.andExpect(jsonPath("$.author.displayName").value(author.getDisplayName()))
				.andExpect(jsonPath("$.reviewedBy").value(nullValue()))
				.andExpect(jsonPath("$.reviewedAt").value(nullValue()))
				.andExpect(jsonPath("$.updatedAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		long id = JsonPath.<Number>read(created, "$.id").longValue();
		entityManager.flush();
		entityManager.clear();
		var loaded = postmortems.findByIncidentId(incident.getId()).orElseThrow();
		assertThat(loaded.getId()).isEqualTo(id);
		assertThat(loaded.getSummary()).isEqualTo("Checkout unavailable");
		assertThat(loaded.getImpact()).isEqualTo("Orders delayed");
		assertThat(loaded.getRootCause()).isEqualTo("Pool exhausted");
		assertThat(loaded.getResolution()).isEqualTo("Raised pool limit");
		assertThat(loaded.getWentWell()).isEqualTo("Alert fired quickly");
		assertThat(loaded.getWentBadly()).isEqualTo("Missing pool metric");
		assertThat(loaded.getUpdatedAt()).isNotNull();
		mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION, bearer(reviewerToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary").value(loaded.getSummary()))
				.andExpect(jsonPath("$.impact").value(loaded.getImpact()))
				.andExpect(jsonPath("$.rootCause").value(loaded.getRootCause()))
				.andExpect(jsonPath("$.resolution").value(loaded.getResolution()))
				.andExpect(jsonPath("$.wentWell").value(loaded.getWentWell()))
				.andExpect(jsonPath("$.wentBadly").value(loaded.getWentBadly()));
		long originalVersion = loaded.getVersion();
		save(reviewerToken, request(originalVersion)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.author.id").value(reviewer.getId()))
				.andExpect(jsonPath("$.version").value(originalVersion + 1));
		assertThat(postmortems.count()).isEqualTo(1);
		assertThat(incidents.findById(incident.getId()).orElseThrow().getVersion()).isEqualTo(incident.getVersion());
	}

	@ParameterizedTest
	@ValueSource(strings = { "summary", "impact", "rootCause", "resolution", "wentWell", "wentBadly" })
	void everyFieldAcceptsTenThousandAndRejectsTenThousandAndOne(String field) throws Exception {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put(field, "x".repeat(10000));
		String created = mvc.perform(put(path()).header(HttpHeaders.AUTHORIZATION, bearer(authorToken))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(values)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		values.put("expectedVersion", version(created));
		values.put(field, "x".repeat(10001));
		mvc.perform(put(path()).header(HttpHeaders.AUTHORIZATION, bearer(authorToken))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(values)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors." + field).exists());
		entityManager.clear();
		mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION, bearer(authorToken)))
				.andExpect(status().isOk()).andExpect(jsonPath("$." + field).value("x".repeat(10000)));
	}

	@Test
	void staleUpdateAndReviewLeaveTheSavedDraftAndTimelineUnchanged() throws Exception {
		String created = save(authorToken, request(null)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		long oldVersion = version(created);
		String updated = save(reviewerToken, request(oldVersion)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		save(authorToken, request(oldVersion)).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(STALE));
		review(authorToken, oldVersion).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(STALE));
		entityManager.clear();
		var loaded = postmortems.findByIncidentId(incident.getId()).orElseThrow();
		assertThat(loaded.getVersion()).isEqualTo(version(updated));
		assertThat(loaded.getAuthor().getId()).isEqualTo(reviewer.getId());
		assertThat(loaded.getStatus()).isEqualTo(PostmortemStatus.DRAFT);
		assertThat(timeline.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId())).isEmpty();
	}

	@Test
	void onlyAnotherPersonCanReviewAndEditingClearsThatReview() throws Exception {
		String created = save(authorToken, request(null)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		review(authorToken, version(created)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Someone other than the author must review the postmortem"));
		assertThat(timeline.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId())).isEmpty();
		String reviewed = review(reviewerToken, version(created)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REVIEWED"))
				.andExpect(jsonPath("$.reviewedBy.id").value(reviewer.getId()))
				.andExpect(jsonPath("$.reviewedBy.displayName").value(reviewer.getDisplayName()))
				.andExpect(jsonPath("$.reviewedAt").isNotEmpty())
				.andExpect(jsonPath("$.version").value(version(created) + 1))
				.andReturn().getResponse().getContentAsString();
		entityManager.clear();
		var saved = postmortems.findByIncidentId(incident.getId()).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(PostmortemStatus.REVIEWED);
		assertThat(saved.getReviewedAt()).isNotNull();
		var entries = timeline.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		assertThat(entries).hasSize(1);
		assertThat(entries.getFirst().getActor().getId()).isEqualTo(reviewer.getId());
		assertThat(entries.getFirst().getEventType()).isEqualTo(TimelineEventType.POSTMORTEM_REVIEWED);
		assertThat(entries.getFirst().getDetails()).isEqualTo("Postmortem reviewed by " + reviewer.getDisplayName());
		assertThat(entries.getFirst().getRecordedAt()).isEqualTo(saved.getReviewedAt());
		save(reviewerToken, request(version(reviewed))).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.author.id").value(reviewer.getId()))
				.andExpect(jsonPath("$.reviewedBy").value(nullValue()))
				.andExpect(jsonPath("$.reviewedAt").value(nullValue()));
		entityManager.clear();
		var redrafted = postmortems.findByIncidentId(incident.getId()).orElseThrow();
		assertThat(redrafted.getReviewedAt()).isNull();
		assertThat(redrafted.getReviewedBy()).isNull();
		assertThat(redrafted.getAuthor().getId()).isEqualTo(reviewer.getId());
		assertThat(timeline.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId())).hasSize(1);
		assertThat(incidents.findById(incident.getId()).orElseThrow().getVersion()).isEqualTo(incident.getVersion());
	}

	@Test
	void nullVersionMeansCreateAndCannotOverwriteAnExistingDraft() throws Exception {
		save(authorToken, request(0L)).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(STALE));
		assertThat(postmortems.findByIncidentId(incident.getId())).isEmpty();
		String created = save(authorToken, request(null)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		save(reviewerToken, request(null)).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(STALE));
		entityManager.clear();
		var loaded = postmortems.findByIncidentId(incident.getId()).orElseThrow();
		assertThat(loaded.getVersion()).isEqualTo(version(created));
		assertThat(loaded.getAuthor().getId()).isEqualTo(author.getId());
		assertThat(postmortems.count()).isEqualTo(1);
	}

	@Test
	void repeatingACurrentReviewKeepsItsOriginalReviewerTimeAndTimeline() throws Exception {
		String created = save(authorToken, request(null)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String reviewed = review(reviewerToken, version(created)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		entityManager.clear();
		var first = postmortems.findByIncidentId(incident.getId()).orElseThrow();
		var reviewedAt = first.getReviewedAt();
		review(reviewerToken, version(reviewed)).andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(version(reviewed)))
				.andExpect(jsonPath("$.reviewedBy.id").value(reviewer.getId()));
		entityManager.clear();
		assertThat(postmortems.findByIncidentId(incident.getId()).orElseThrow().getReviewedAt()).isEqualTo(reviewedAt);
		assertThat(timeline.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId())).hasSize(1);
		review(reviewerToken, version(created)).andExpect(status().isConflict());
	}

	@Test
	void missingPostmortemIsNotFoundOnGetAndReview() throws Exception {
		mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION, bearer(authorToken))).andExpect(status().isNotFound());
		review(reviewerToken, 0).andExpect(status().isNotFound());
		mvc.perform(put("/api/incidents/999999999/postmortem").header(HttpHeaders.AUTHORIZATION, bearer(authorToken))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound());
	}

	@ParameterizedTest
	@EnumSource(value = Severity.class, names = { "SEV1", "SEV2" })
	void majorIncidentRequiresReviewAndEditingRevokesClosurePermission(Severity severity) throws Exception {
		incident = createIncident(severity);
		assertThatThrownBy(() -> service.requireReviewedForClosure(incident))
				.isInstanceOf(ConflictException.class).hasMessage(CLOSURE);
		String created = save(authorToken, request(null)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThatThrownBy(() -> service.requireReviewedForClosure(incident))
				.isInstanceOf(ConflictException.class).hasMessage(CLOSURE);
		String reviewed = review(reviewerToken, version(created)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		entityManager.clear();
		assertThatCode(() -> service.requireReviewedForClosure(incident)).doesNotThrowAnyException();
		save(authorToken, request(version(reviewed))).andExpect(status().isOk());
		assertThatThrownBy(() -> service.requireReviewedForClosure(incident))
				.isInstanceOf(ConflictException.class).hasMessage(CLOSURE);
	}

	@ParameterizedTest
	@EnumSource(value = Severity.class, names = { "SEV3", "SEV4" })
	void minorIncidentMayCloseWithoutAPostmortem(Severity severity) {
		Incident minor = createIncident(severity);
		assertThatCode(() -> service.requireReviewedForClosure(minor)).doesNotThrowAnyException();
		assertThat(postmortems.findByIncidentId(minor.getId())).isEmpty();
	}

	private Incident createIncident(Severity severity) {
		Incident saved = new Incident("Checkout failing", "Errors", payments, severity, IncidentStatus.RESOLVED,
				author, clock.instant(), 15, 240);
		saved.assignNumber(nextNumber++);
		return incidents.saveAndFlush(saved);
	}

	private PostmortemRequest request(Long expectedVersion) {
		return new PostmortemRequest("Checkout unavailable", "Orders delayed", "Pool exhausted", "Raised pool limit",
				"Alert fired quickly", "Missing pool metric", expectedVersion);
	}

	private ResultActions save(String token, PostmortemRequest request) throws Exception {
		return mvc.perform(put(path()).header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
	}

	private ResultActions review(String token, long expectedVersion) throws Exception {
		return mvc.perform(post(path() + "/review").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":%d}".formatted(expectedVersion)));
	}

	private String path() {
		return "/api/incidents/" + incident.getId() + "/postmortem";
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static long version(String body) {
		return JsonPath.<Number>read(body, "$.version").longValue();
	}
}
