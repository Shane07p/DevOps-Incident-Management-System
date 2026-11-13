package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.deploy.entity.Deployment;
import com.group2.incident.deploy.repository.DeploymentRepository;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class IncidentControllerTest {

	private static final String PASSWORD = "DevPassw0rd!";

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private TimelineEntryRepository timelineEntries;

	@Autowired
	private DeploymentRepository deployments;

	@Autowired
	private AuditEntryRepository auditEntries;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	private AppUser engineer;
	private AppUser viewer;
	private ServiceEntity paymentsService;
	private long nextIncidentNumber;

	@BeforeEach
	void setUp() {
		Instant now = clock.instant();
		nextIncidentNumber = 1L;
		engineer = accounts.save(new AppUser("engineer@test.local", "Dev Engineer", passwordEncoder.encode(PASSWORD), now), Role.ENGINEER);
		viewer = accounts.save(new AppUser("viewer@test.local", "Dev Viewer", passwordEncoder.encode(PASSWORD), now), Role.VIEWER);

		ServiceEntity payments = new ServiceEntity("Payments", "Card payments", engineer, Criticality.CRITICAL, now);
		payments.putTarget(Severity.SEV1, 15, 240);
		payments.putTarget(Severity.SEV2, 60, 480);
		payments.putTarget(Severity.SEV3, 240, 2880);
		payments.putTarget(Severity.SEV4, 1440, 10080);
		paymentsService = services.saveAndFlush(payments);
	}

	@Test
	@DisplayName("TC-FR-02.1a: Create incident with all fields returns 201 with status OPEN, copied SLA, timeline and audit")
	void createIncidentReturns201AndOpenStatus() throws Exception {
		String token = tokenFor("engineer");

		String body = """
				{
					"title": "Checkout failing",
					"description": "Database connection timeouts in checkout service",
					"serviceId": %d,
					"severity": "SEV2"
				}
				""".formatted(paymentsService.getId());

		ResultActions result = mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.startsWith("INC-")))
				.andExpect(jsonPath("$.title").value("Checkout failing"))
				.andExpect(jsonPath("$.description").value("Database connection timeouts in checkout service"))
				.andExpect(jsonPath("$.service.id").value(paymentsService.getId()))
				.andExpect(jsonPath("$.service.name").value("Payments"))
				.andExpect(jsonPath("$.severity").value("SEV2"))
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.reporter.displayName").value("Dev Engineer"))
				.andExpect(jsonPath("$.responseTargetMinutes").value(60))
				.andExpect(jsonPath("$.resolutionTargetMinutes").value(480))
				.andExpect(jsonPath("$.responseDueAt").isNotEmpty())
				.andExpect(jsonPath("$.resolutionDueAt").isNotEmpty())
				.andExpect(jsonPath("$.timeline[0].eventType").value("INCIDENT_CREATED"))
				.andExpect(jsonPath("$.timeline[0].actor").value("Dev Engineer"))
				.andExpect(jsonPath("$.timeline[0].details").value("Reported as SEV2 on Payments"));

		Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
		assertThat(auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(AuditItemType.INCIDENT, id.longValue()))
				.extracting(AuditEntry::getAction)
				.containsExactly(AuditAction.INCIDENT_CREATED);
	}

	@Test
	@DisplayName("TC-FR-02.1b: Required fields omitted one at a time return 400 naming the field")
	void missingRequiredFieldsReturn400NamingField() throws Exception {
		String token = tokenFor("engineer");

		// Missing title
		mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"description\":\"desc\",\"serviceId\":%d,\"severity\":\"SEV2\"}".formatted(paymentsService.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.title").value("Title is required"));

		// Missing description
		mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Title\",\"serviceId\":%d,\"severity\":\"SEV2\"}".formatted(paymentsService.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.description").value("Description is required"));

		// Missing serviceId
		mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Title\",\"description\":\"desc\",\"severity\":\"SEV2\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.serviceId").value("Service is required"));

		// Missing severity
		mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Title\",\"description\":\"desc\",\"serviceId\":%d}".formatted(paymentsService.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.severity").value("Severity is required"));
	}

	@Test
	@DisplayName("Unknown service returns 400 naming serviceId")
	void unknownServiceReturns400() throws Exception {
		String token = tokenFor("engineer");
		String body = "{\"title\":\"Title\",\"description\":\"desc\",\"serviceId\":999999,\"severity\":\"SEV2\"}";

		mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.serviceId").value("Service not found"));
	}

	@Test
	@DisplayName("TC-FR-02.2a: Two incidents created get distinct IDs and INC- references")
	void twoIncidentsGetDistinctIdsAndReferences() throws Exception {
		String token = tokenFor("engineer");

		String body1 = "{\"title\":\"First\",\"description\":\"First desc\",\"serviceId\":%d,\"severity\":\"SEV1\"}".formatted(paymentsService.getId());
		String body2 = "{\"title\":\"Second\",\"description\":\"Second desc\",\"serviceId\":%d,\"severity\":\"SEV2\"}".formatted(paymentsService.getId());

		String res1 = mvc.perform(post("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body1)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String res2 = mvc.perform(post("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body2)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

		Number id1 = JsonPath.read(res1, "$.id");
		Number id2 = JsonPath.read(res2, "$.id");
		String ref1 = JsonPath.read(res1, "$.reference");
		String ref2 = JsonPath.read(res2, "$.reference");

		assertThat(id1).isNotEqualTo(id2);
		// The org numbers its incidents in order (D48), so the second takes the next number.
		assertThat(ref1).matches("INC-\\d+");
		assertThat(ref2).isEqualTo("INC-" + (Long.parseLong(ref1.substring(4)) + 1));
	}

	@Test
	@DisplayName("TC-FR-02.2b: List incidents returns newest first")
	void listIncidentsReturnsNewestFirst() throws Exception {
		String token = tokenFor("engineer");

		Instant now = clock.instant();
		Incident firstUnsaved = new Incident("First Incident", "Desc 1", paymentsService,
				Severity.SEV3, IncidentStatus.OPEN, engineer, now.minusSeconds(100), 240, 2880);
		firstUnsaved.assignNumber(901L);
		Incident first = incidents.saveAndFlush(firstUnsaved);
		Incident secondUnsaved = new Incident("Second Incident", "Desc 2", paymentsService,
				Severity.SEV2, IncidentStatus.OPEN, engineer, now, 60, 480);
		secondUnsaved.assignNumber(902L);
		Incident second = incidents.saveAndFlush(secondUnsaved);

		mvc.perform(get("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(second.getId()))
				.andExpect(jsonPath("$[0].reference").value("INC-902"))
				.andExpect(jsonPath("$[0].serviceName").value("Payments"))
				// The board sorts by time left, so each row carries both due times (TC-FR-16.3f).
				.andExpect(jsonPath("$[0].responseDueAt").value(second.getResponseDueAt().toString()))
				.andExpect(jsonPath("$[0].resolutionDueAt").value(second.getResolutionDueAt().toString()))
				.andExpect(jsonPath("$[1].id").value(first.getId()))
				.andExpect(jsonPath("$[1].reference").value("INC-901"))
				.andExpect(jsonPath("$[1].responseDueAt").value(first.getResponseDueAt().toString()));
	}

	@Test
	@DisplayName("TC-FR-04.1a: Severity SEV1 to SEV4 accepted; SEV5 and blank rejected with 400")
	void severityAcceptsValidAndRejectsInvalid() throws Exception {
		String token = tokenFor("engineer");

		for (Severity sev : Severity.values()) {
			String body = "{\"title\":\"Title\",\"description\":\"desc\",\"serviceId\":%d,\"severity\":\"%s\"}"
					.formatted(paymentsService.getId(), sev.name());
			mvc.perform(post("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
							.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.severity").value(sev.name()));
		}

		// SEV5
		String invalidSev = "{\"title\":\"Title\",\"description\":\"desc\",\"serviceId\":%d,\"severity\":\"SEV5\"}"
				.formatted(paymentsService.getId());
		mvc.perform(post("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content(invalidSev))
				.andExpect(status().isBadRequest());

		// Blank severity
		String blankSev = "{\"title\":\"Title\",\"description\":\"desc\",\"serviceId\":%d,\"severity\":\"\"}"
				.formatted(paymentsService.getId());
		mvc.perform(post("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content(blankSev))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Get incident details by id returns full detail including timeline")
	void getIncidentByIdReturnsFullDetail() throws Exception {
		String token = tokenFor("engineer");

		String createBody = """
				{
					"title": "Payment gateway down",
					"description": "503 Service Unavailable",
					"serviceId": %d,
					"severity": "SEV1"
				}
				""".formatted(paymentsService.getId());

		String res = mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		Number id = JsonPath.read(res, "$.id");

		mvc.perform(get("/api/incidents/" + id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.reference").value(JsonPath.<String>read(res, "$.reference")))
				.andExpect(jsonPath("$.title").value("Payment gateway down"))
				.andExpect(jsonPath("$.timeline[0].eventType").value("INCIDENT_CREATED"));
	}

	@Test
	@DisplayName("Get unknown incident returns 404")
	void getUnknownIncidentReturns404() throws Exception {
		String token = tokenFor("engineer");

		mvc.perform(get("/api/incidents/999999")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));
	}

	/** Confirms comments persist on the timeline without changing the incident version. */
	@Test
	@DisplayName("TC-FR-09.1a: Comments are separate timeline inserts and do not change the incident version")
	void commentsDoNotChangeIncidentVersion() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN);
		Long version = incident.getVersion();
		String token = tokenFor("engineer");

		for (String comment : List.of("First note", "Second note")) {
			mvc.perform(post("/api/incidents/" + incident.getId() + "/comments")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"comment\":\"" + comment + "\"}"))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.eventType").value("COMMENT"))
					.andExpect(jsonPath("$.actor").value("Dev Engineer"))
					.andExpect(jsonPath("$.details").value(comment))
					.andExpect(jsonPath("$.correctsEntryId").value(org.hamcrest.Matchers.nullValue()));
		}

		assertThat(incidents.findById(incident.getId()).orElseThrow().getVersion()).isEqualTo(version);
		assertThat(timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId()))
				.extracting(TimelineEntry::getDetails)
				.containsExactly("First note", "Second note");
		mvc.perform(get("/api/incidents/" + incident.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.timeline[0].details").value("First note"))
				.andExpect(jsonPath("$.timeline[1].details").value("Second note"));
	}

	/** Confirms a follow-up comment leaves a closed incident closed. */
	@Test
	@DisplayName("TC-FR-09.2a: A comment on a CLOSED incident does not reopen it")
	void commentsAreAllowedOnClosedIncidents() throws Exception {
		Incident incident = createIncident(IncidentStatus.CLOSED);
		Long version = incident.getVersion();
		String token = tokenFor("engineer");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/comments")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"comment\":\"Follow-up after closure\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.details").value("Follow-up after closure"));

		Incident unchanged = incidents.findById(incident.getId()).orElseThrow();
		assertThat(unchanged.getStatus()).isEqualTo(IncidentStatus.CLOSED);
		assertThat(unchanged.getVersion()).isEqualTo(version);
	}

	/** Confirms corrections append a linked entry and preserve the original details. */
	@Test
	@DisplayName("TC-FR-08.3c: A correction is linked to its original timeline entry")
	void correctionAddsLinkedEntryAndLeavesOriginalUnchanged() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN);
		TimelineEntry original = timelineEntries.saveAndFlush(new TimelineEntry(
				incident, engineer, TimelineEventType.STATUS_CHANGED, "Status changed to IDENTIFIED", clock.instant()));
		String token = tokenFor("engineer");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/timeline/" + original.getId() + "/corrections")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"details\":\"Corrected status: INVESTIGATING\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.eventType").value("CORRECTION"))
				.andExpect(jsonPath("$.details").value("Corrected status: INVESTIGATING"))
				.andExpect(jsonPath("$.correctsEntryId").value(original.getId()));

		assertThat(timelineEntries.findById(original.getId()).orElseThrow().getDetails())
				.isEqualTo("Status changed to IDENTIFIED");
		mvc.perform(get("/api/incidents/" + incident.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.timeline[1].correctsEntryId").value(original.getId()));
	}

	/** Rejects corrections that try to reference another incident's timeline. */
	@Test
	void correctionCannotTargetAnEntryFromAnotherIncident() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN);
		Incident otherIncident = createIncident(IncidentStatus.OPEN);
		TimelineEntry otherEntry = timelineEntries.saveAndFlush(new TimelineEntry(
				otherIncident, engineer, TimelineEventType.COMMENT, "Other incident", clock.instant()));
		String token = tokenFor("engineer");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/timeline/" + otherEntry.getId() + "/corrections")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"details\":\"Invalid cross-incident correction\"}"))
				.andExpect(status().isNotFound());
	}

	/** Accepts exactly 5,000 characters and rejects longer comments and corrections. */
	@Test
	void commentAndCorrectionEnforce5000CharacterLimit() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN);
		TimelineEntry original = timelineEntries.saveAndFlush(new TimelineEntry(
				incident, engineer, TimelineEventType.COMMENT, "Original note", clock.instant()));
		String token = tokenFor("engineer");
		String maxLengthText = "x".repeat(5000);
		String oversizedText = "x".repeat(5001);

		mvc.perform(post("/api/incidents/" + incident.getId() + "/comments")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"comment\":\"" + maxLengthText + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.details").value(maxLengthText));

		mvc.perform(post("/api/incidents/" + incident.getId() + "/timeline/" + original.getId() + "/corrections")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"details\":\"" + maxLengthText + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.details").value(maxLengthText));

		mvc.perform(post("/api/incidents/" + incident.getId() + "/comments")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"comment\":\"" + oversizedText + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.comment").value("Comment must not exceed 5000 characters"));

		mvc.perform(post("/api/incidents/" + incident.getId() + "/timeline/" + original.getId() + "/corrections")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"details\":\"" + oversizedText + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.details").value("Correction details must not exceed 5000 characters"));
	}

	private Incident createIncident(IncidentStatus status) {
		Incident incident = new Incident(
				"Comment test incident",
				"Incident for timeline tests",
				paymentsService,
				Severity.SEV2,
				status,
				engineer,
				clock.instant(),
				60,
				480
		);
		incident.assignNumber(nextIncidentNumber++);
		return incidents.saveAndFlush(incident);
	}

	@Test
	@DisplayName("Viewer cannot create incident (403), but can view incidents and incident detail (200)")
	void viewerPermissions() throws Exception {
		String viewerToken = tokenFor("viewer");

		String body = "{\"title\":\"Viewer Incident\",\"description\":\"Desc\",\"serviceId\":%d,\"severity\":\"SEV2\"}"
				.formatted(paymentsService.getId());

		mvc.perform(post("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isForbidden());

		mvc.perform(get("/api/incidents")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("Evidence links are added to the timeline without changing incident version")
	void evidenceLinkIsAddedToTimeline() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN);
		Long originalVersion = incident.getVersion();
		String token = tokenFor("engineer");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/evidence")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
						"url": "https://grafana.example.com/logs",
						"label": "Grafana logs"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.eventType").value("EVIDENCE_ADDED"));

		assertThat(incidents.findById(incident.getId()).orElseThrow().getVersion())
				.isEqualTo(originalVersion);

		mvc.perform(get("/api/incidents/" + incident.getId())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.timeline[0].eventType").value("EVIDENCE_ADDED"))
			.andExpect(jsonPath("$.timeline[0].details").value("Grafana logs: https://grafana.example.com/logs"));
	}

	@Test
	@DisplayName("Invalid evidence URL is rejected")
	void invalidEvidenceUrlReturns400() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN);
		String token = tokenFor("engineer");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/evidence")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
						"url": "javascript:alert(1)",
						"label": "Unsafe link"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.url").exists());
		mvc.perform(post("/api/incidents/" + incident.getId() + "/evidence")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"ftp://example.com/logs\",\"label\":\"FTP logs\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.url").exists());

	}

	@Test
	@DisplayName("Evidence can be added to a closed incident")
	void evidenceAllowedOnClosedIncident() throws Exception {
		Incident incident = createIncident(IncidentStatus.CLOSED);
		Long originalVersion = incident.getVersion();
		String token = tokenFor("engineer");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/evidence")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
						"url": "https://example.com/report",
						"label": "Incident report"
					}
					"""))
			.andExpect(status().isCreated());

		Incident unchanged = incidents.findById(incident.getId()).orElseThrow();
		assertThat(unchanged.getStatus()).isEqualTo(IncidentStatus.CLOSED);
		assertThat(unchanged.getVersion()).isEqualTo(originalVersion);
	}

	@Test
	@DisplayName("Deployment can be linked to a closed incident without changing status or version")
	void deployAllowedOnClosedIncident() throws Exception {
		Incident incident = createIncident(IncidentStatus.CLOSED);
		Long originalVersion = incident.getVersion();
		Deployment deployment = deployments.saveAndFlush(new Deployment(paymentsService, "v1.9.9", "hash199", "Author", clock.instant()));
		String token = tokenFor("engineer");

		mvc.perform(post("/api/incidents/" + incident.getId() + "/deploys/" + deployment.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventType").value("DEPLOY_LINKED"));

		Incident unchanged = incidents.findById(incident.getId()).orElseThrow();
		assertThat(unchanged.getStatus()).isEqualTo(IncidentStatus.CLOSED);
		assertThat(unchanged.getVersion()).isEqualTo(originalVersion);
	}

	private ResultActions login(String username, String password) throws Exception {
		String body = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(username + "@test.local", password);
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String tokenFor(String username) throws Exception {
		return JsonPath.read(login(username, PASSWORD).andReturn().getResponse().getContentAsString(), "$.token");
	}
}
