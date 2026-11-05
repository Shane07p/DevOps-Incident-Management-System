package com.group2.incident.incident.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class IncidentSearchTest {

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private TimelineEntryRepository timelineEntries;

	@Autowired
	private Clock clock;

	private AppUser engineer;
	private ServiceEntity payments;
	private ServiceEntity identity;
	private String token;
	private long nextIncidentNumber;

	/** Creates an engineer, services, and an authenticated search token for each test. */
	@BeforeEach
	void setUp() throws Exception {
		engineer = accounts.create("search-engineer@test.local", Role.ENGINEER);
		token = accounts.token("search-engineer@test.local");
		payments = createService("Search Payments");
		identity = createService("Search Identity");
		nextIncidentNumber = clock.millis() * 1000;
	}

	/** Finds text independently in each searchable field and reports the matched field. */
	@Test
	void findsMatchesInTitleDescriptionAndTimelineAndReportsMatchLocation() throws Exception {
		Incident titleMatch = createIncident("titlequartz failure", "No special details", payments,
				Severity.SEV2, IncidentStatus.OPEN);
		Incident descriptionMatch = createIncident("Description incident", "descriptionzircon timeout", payments,
				Severity.SEV2, IncidentStatus.OPEN);
		Incident timelineMatch = createIncident("Timeline incident", "No special details", payments,
				Severity.SEV2, IncidentStatus.OPEN);
		timelineEntries.saveAndFlush(new TimelineEntry(
				timelineMatch, engineer, TimelineEventType.COMMENT, "timelinecobalt evidence", clock.instant()));

		search("titlequartz")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].id").value(titleMatch.getId().intValue()))
				.andExpect(jsonPath("$.content[0].matchedIn[0]").value("title"));

		search("descriptionzircon")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].id").value(descriptionMatch.getId().intValue()))
				.andExpect(jsonPath("$.content[0].matchedIn[0]").value("description"));

		search("timelinecobalt")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].id").value(timelineMatch.getId().intValue()))
				.andExpect(jsonPath("$.content[0].matchedIn[0]").value("timeline"));
	}

	/** Applies service, severity, and status filters individually and in combination. */
	@Test
	void appliesServiceSeverityAndStatusFiltersTogether() throws Exception {
		Incident target = createIncident("filtermarker target", "shared words", payments,
				Severity.SEV1, IncidentStatus.RESOLVED);
		createIncident("filtermarker wrong service", "shared words", identity,
				Severity.SEV1, IncidentStatus.RESOLVED);
		createIncident("filtermarker wrong severity", "shared words", payments,
				Severity.SEV2, IncidentStatus.RESOLVED);
		createIncident("filtermarker wrong status", "shared words", payments,
				Severity.SEV1, IncidentStatus.OPEN);

		search("filtermarker")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(4)));

		search("filtermarker", payments.getId(), null, null, 0)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(3)));

		search("filtermarker", null, Severity.SEV1, null, 0)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(3)));

		search("filtermarker", null, null, IncidentStatus.RESOLVED, 0)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(3)));

		search("filtermarker", payments.getId(), Severity.SEV1, IncidentStatus.RESOLVED, 0)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].id").value(target.getId().intValue()));
	}

	/** Enforces 20-result pages and validates empty results and invalid query parameters. */
	@Test
	void returnsTwentyResultsPerPageAndHandlesEmptyOrInvalidQueries() throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 21; i++) {
			ids.add(createIncident("pagemarker incident " + i, "pagination fixture", payments,
					Severity.SEV3, IncidentStatus.INVESTIGATING).getId());
		}

		search("pagemarker")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(20)))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(21))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.content[0].id").value(ids.get(20).intValue()));

		search("pagemarker", null, null, null, 1)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].id").value(ids.get(0).intValue()));

		search("not-a-real-incident-word")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(0)))
				.andExpect(jsonPath("$.totalElements").value(0));

		search("")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.q").value("Search query must not be blank"));

		search("pagemarker", null, null, null, -1)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.page").value("must not be negative"));
	}

	/** Saves a service with all severity targets required by incident creation. */
	private ServiceEntity createService(String name) {
		ServiceEntity service = new ServiceEntity(name, "Search test service", engineer,
				Criticality.CRITICAL, clock.instant());
		service.putTarget(Severity.SEV1, 15, 240);
		service.putTarget(Severity.SEV2, 60, 480);
		service.putTarget(Severity.SEV3, 240, 2880);
		service.putTarget(Severity.SEV4, 1440, 10080);
		return services.saveAndFlush(service);
	}

	/** Persists an incident fixture with the given searchable text and filters. */
	private Incident createIncident(
			String title,
			String description,
			ServiceEntity service,
			Severity severity,
			IncidentStatus status
	) {
		Incident incident = new Incident(title, description, service, severity, status, engineer,
				clock.instant(), 60, 480);
		incident.assignNumber(nextIncidentNumber++);
		return incidents.saveAndFlush(incident);
	}

	/** Searches the first page without optional filters. */
	private org.springframework.test.web.servlet.ResultActions search(String query) throws Exception {
		return search(query, null, null, null, 0);
	}

	/** Calls the search endpoint with the supplied keyword, filters, and page. */
	private org.springframework.test.web.servlet.ResultActions search(
			String query,
			Long serviceId,
			Severity severity,
			IncidentStatus status,
			int page
	) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/incidents/search")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.queryParam("q", query)
				.queryParam("page", Integer.toString(page));
		if (serviceId != null) {
			request.queryParam("serviceId", serviceId.toString());
		}
		if (severity != null) {
			request.queryParam("severity", severity.name());
		}
		if (status != null) {
			request.queryParam("status", status.name());
		}
		return mvc.perform(request);
	}
}
