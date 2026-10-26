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
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
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
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private AuditEntryRepository auditEntries;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	private AppUser engineer;
	private AppUser viewer;
	private ServiceEntity paymentsService;

	@BeforeEach
	void setUp() {
		Instant now = clock.instant();
		engineer = users.saveAndFlush(new AppUser("engineer", "Dev Engineer", passwordEncoder.encode(PASSWORD),
				Role.ENGINEER, now));
		viewer = users.saveAndFlush(new AppUser("viewer", "Dev Viewer", passwordEncoder.encode(PASSWORD),
				Role.VIEWER, now));

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
		assertThat(ref1).isEqualTo("INC-" + id1);
		assertThat(ref2).isEqualTo("INC-" + id2);
	}

	@Test
	@DisplayName("TC-FR-02.2b: List incidents returns newest first")
	void listIncidentsReturnsNewestFirst() throws Exception {
		String token = tokenFor("engineer");

		Instant now = clock.instant();
		Incident first = incidents.saveAndFlush(new Incident("First Incident", "Desc 1", paymentsService,
				Severity.SEV3, IncidentStatus.OPEN, engineer, now.minusSeconds(100), 240, 2880));
		Incident second = incidents.saveAndFlush(new Incident("Second Incident", "Desc 2", paymentsService,
				Severity.SEV2, IncidentStatus.OPEN, engineer, now, 60, 480));

		mvc.perform(get("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(second.getId()))
				.andExpect(jsonPath("$[0].reference").value("INC-" + second.getId()))
				.andExpect(jsonPath("$[0].serviceName").value("Payments"))
				.andExpect(jsonPath("$[1].id").value(first.getId()))
				.andExpect(jsonPath("$[1].reference").value("INC-" + first.getId()));
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
				.andExpect(jsonPath("$.reference").value("INC-" + id))
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

	private ResultActions login(String username, String password) throws Exception {
		String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String tokenFor(String username) throws Exception {
		return JsonPath.read(login(username, PASSWORD).andReturn().getResponse().getContentAsString(), "$.token");
	}
}
