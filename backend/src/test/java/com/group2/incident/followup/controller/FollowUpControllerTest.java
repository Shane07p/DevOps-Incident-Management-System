package com.group2.incident.followup.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.followup.service.FollowUpService;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Follow-ups (#240, FR-11.3), through the API, with the service's clock held at a fixed time. */
@IntegrationTest
class FollowUpControllerTest {

	// 14 Nov 2026, late in the UTC day: still the 14th in UTC although it is already the 15th in IST.
	private static final Instant NOW = Instant.parse("2026-11-14T20:00:00Z");

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private TimelineEntryRepository timeline;

	@Autowired
	private FollowUpService followUpService;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Clock clock;

	private AppUser admin;

	private AppUser engineer;

	private String adminToken;

	private String engineerToken;

	private String viewerToken;

	private ServiceEntity service;

	private Long incidentId;

	@BeforeEach
	void setUp() throws Exception {
		holdClockAt(NOW);
		admin = accounts.create("follow-admin@test.local", Role.ADMIN);
		engineer = accounts.create("follow-engineer@test.local", Role.ENGINEER);
		accounts.create("follow-viewer@test.local", Role.VIEWER);
		adminToken = accounts.token("follow-admin@test.local");
		engineerToken = accounts.token("follow-engineer@test.local");
		viewerToken = accounts.token("follow-viewer@test.local");

		ServiceEntity unsaved = new ServiceEntity("Follow-up payments", "Payments", admin, Criticality.HIGH, clock.instant());
		for (Severity severity : Severity.values()) {
			unsaved.putTarget(severity, 60, 480);
		}
		service = services.saveAndFlush(unsaved);
		incidentId = incident(880_001L).getId();
	}

	@AfterEach
	void resetClock() {
		ReflectionTestUtils.setField(followUpService, "clock", clock);
	}

	@Test
	void createsACorrectiveAndAPreventiveFollowUp() throws Exception {
		String corrective = create(engineerToken, incidentId, "Add pool checks to the deploy checklist", "CORRECTIVE",
				engineer.getId(), "2026-11-20", 201);
		String preventive = create(adminToken, incidentId, "Alert when the pool is 80% used", "PREVENTIVE",
				admin.getId(), "2026-11-27", 201);

		assertThat(JsonPath.<String>read(corrective, "$.kind")).isEqualTo("CORRECTIVE");
		assertThat(JsonPath.<String>read(preventive, "$.kind")).isEqualTo("PREVENTIVE");
		assertThat(JsonPath.<Integer>read(corrective, "$.incidentId").longValue()).isEqualTo(incidentId);
		assertThat(JsonPath.<String>read(corrective, "$.incidentReference")).isEqualTo("INC-880001");
		assertThat(JsonPath.<String>read(corrective, "$.title")).isEqualTo("Add pool checks to the deploy checklist");
		assertThat(JsonPath.<Integer>read(corrective, "$.owner.id").longValue()).isEqualTo(engineer.getId());
		assertThat(JsonPath.<String>read(corrective, "$.owner.displayName")).isEqualTo("follow-engineer");
		assertThat(JsonPath.<String>read(corrective, "$.dueDate")).isEqualTo("2026-11-20");
		assertThat(JsonPath.<String>read(corrective, "$.status")).isEqualTo("OPEN");
		assertThat(JsonPath.<Boolean>read(corrective, "$.overdue")).isFalse();
		assertThat(JsonPath.<Object>read(corrective, "$.completedAt")).isNull();
		assertThat(JsonPath.<String>read(corrective, "$.createdAt")).isEqualTo("2026-11-14T20:00:00Z");

		List<TimelineEntry> added = entries(TimelineEventType.FOLLOW_UP_ADDED);
		assertThat(added).hasSize(2);
		assertThat(added.getFirst().getDetails())
				.isEqualTo("Follow-up added (Corrective): Add pool checks to the deploy checklist. Owner follow-engineer, due 2026-11-20");
		assertThat(added.getFirst().getActor().getId()).isEqualTo(engineer.getId());
	}

	@Test
	void aDueDateBeforeTodayInUtcIsRefusedAndTodayIsAccepted() throws Exception {
		String refused = create(engineerToken, incidentId, "Too late", "CORRECTIVE", engineer.getId(), "2026-11-13", 400);
		assertThat(JsonPath.<String>read(refused, "$.errors.dueDate")).isEqualTo("The due date cannot be before today (UTC)");

		// The 15th has begun in IST, but today is still the 14th in UTC.
		create(engineerToken, incidentId, "Due today", "CORRECTIVE", engineer.getId(), TODAY.toString(), 201);
		assertThat(followUpRows()).isEqualTo(1);
	}

	@Test
	void anOwnerFromAnotherOrgOrNoOneIsRefused() throws Exception {
		AppUser outsider = users.saveAndFlush(new AppUser("outsider@other.test", "Outsider", "x", clock.instant()));

		String refused = create(engineerToken, incidentId, "Outsider owns it", "CORRECTIVE", outsider.getId(), "2026-11-20", 400);
		assertThat(JsonPath.<String>read(refused, "$.errors.ownerId")).isEqualTo("The owner must be an active member of this org");
		create(engineerToken, incidentId, "Nobody owns it", "CORRECTIVE", 999_999_999L, "2026-11-20", 400);

		assertThat(followUpRows()).isZero();
	}

	@Test
	void anUnknownIncidentIsNotFound() throws Exception {
		create(engineerToken, 999_999_999L, "Lost", "CORRECTIVE", engineer.getId(), "2026-11-20", 404);
		send(viewerToken, get("/api/incidents/{id}/follow-ups", 999_999_999L), null, 404);
	}

	@Test
	void overdueIsFalseOnTheDueDateAndTrueTheDayAfter() throws Exception {
		create(engineerToken, incidentId, "Rotate the gateway key", "CORRECTIVE", engineer.getId(), "2026-11-20", 201);

		holdClockAt(Instant.parse("2026-11-20T23:59:59Z"));
		assertThat(JsonPath.<List<Boolean>>read(listForIncident(), "$[*].overdue")).containsExactly(false);

		holdClockAt(Instant.parse("2026-11-21T00:00:00Z"));
		assertThat(JsonPath.<List<Boolean>>read(listForIncident(), "$[*].overdue")).containsExactly(true);
	}

	@Test
	void doneSetsCompletedAtAddsATimelineEntryAndIsNeverOverdue() throws Exception {
		long id = idOf(create(engineerToken, incidentId, "Document retries", "CORRECTIVE", engineer.getId(), "2026-11-20", 201));
		Instant doneAt = Instant.parse("2026-11-22T09:30:00Z");
		holdClockAt(doneAt);

		String done = update(engineerToken, id, "{\"status\":\"DONE\"}", 200);

		assertThat(JsonPath.<String>read(done, "$.status")).isEqualTo("DONE");
		assertThat(JsonPath.<String>read(done, "$.completedAt")).isEqualTo("2026-11-22T09:30:00Z");
		assertThat(JsonPath.<Boolean>read(done, "$.overdue")).isFalse();
		assertThat(entries(TimelineEventType.FOLLOW_UP_DONE)).extracting(TimelineEntry::getDetails)
				.containsExactly("Follow-up done: Document retries");

		// Saying DONE again changes nothing and records nothing more.
		update(engineerToken, id, "{\"status\":\"DONE\"}", 200);
		assertThat(entries(TimelineEventType.FOLLOW_UP_DONE)).hasSize(1);

		String reopened = update(engineerToken, id, "{\"status\":\"IN_PROGRESS\"}", 200);
		assertThat(JsonPath.<Object>read(reopened, "$.completedAt")).isNull();
		assertThat(JsonPath.<Boolean>read(reopened, "$.overdue")).isTrue();
	}

	@Test
	void updateChangesOnlyTheFieldsItNames() throws Exception {
		long id = idOf(create(engineerToken, incidentId, "Old title", "CORRECTIVE", engineer.getId(), "2026-11-20", 201));

		String updated = update(adminToken, id,
				"{\"title\":\"New title\",\"kind\":\"PREVENTIVE\",\"ownerId\":%d,\"dueDate\":\"2026-12-01\"}".formatted(admin.getId()), 200);

		assertThat(JsonPath.<String>read(updated, "$.title")).isEqualTo("New title");
		assertThat(JsonPath.<String>read(updated, "$.kind")).isEqualTo("PREVENTIVE");
		assertThat(JsonPath.<Integer>read(updated, "$.owner.id").longValue()).isEqualTo(admin.getId());
		assertThat(JsonPath.<String>read(updated, "$.dueDate")).isEqualTo("2026-12-01");
		assertThat(JsonPath.<String>read(updated, "$.status")).isEqualTo("OPEN");

		String renamedOnly = update(adminToken, id, "{\"title\":\"Newest title\"}", 200);
		assertThat(JsonPath.<String>read(renamedOnly, "$.kind")).isEqualTo("PREVENTIVE");
		assertThat(JsonPath.<String>read(renamedOnly, "$.dueDate")).isEqualTo("2026-12-01");
	}

	@Test
	void anOverdueFollowUpCanBeRenamedButNotMovedIntoThePast() throws Exception {
		long id = idOf(create(engineerToken, incidentId, "Overdue soon", "CORRECTIVE", engineer.getId(), "2026-11-20", 201));
		holdClockAt(Instant.parse("2026-11-25T10:00:00Z"));

		// The due date it already has is in the past now; sending it back unchanged is not a new date.
		update(engineerToken, id, "{\"title\":\"Still overdue\",\"dueDate\":\"2026-11-20\"}", 200);

		String refused = update(engineerToken, id, "{\"dueDate\":\"2026-11-24\"}", 400);
		assertThat(JsonPath.<String>read(refused, "$.errors.dueDate")).isEqualTo("The due date cannot be before today (UTC)");
		String outsiderOwner = update(engineerToken, id, "{\"ownerId\":999999999}", 400);
		assertThat(JsonPath.<String>read(outsiderOwner, "$.errors.ownerId")).isNotBlank();
		update(engineerToken, id, "{\"title\":\"   \"}", 400);
		update(engineerToken, 999_999_999L, "{\"status\":\"DONE\"}", 404);
	}

	@Test
	void aViewerReadsFollowUpsButCannotAddOrChangeThem() throws Exception {
		long id = idOf(create(engineerToken, incidentId, "Viewer can read", "CORRECTIVE", engineer.getId(), "2026-11-20", 201));

		create(viewerToken, incidentId, "Viewer adds", "CORRECTIVE", engineer.getId(), "2026-11-20", 403);
		update(viewerToken, id, "{\"status\":\"DONE\"}", 403);

		assertThat(JsonPath.<List<String>>read(send(viewerToken, get("/api/incidents/{id}/follow-ups", incidentId), null, 200),
				"$[*].title")).containsExactly("Viewer can read");
		send(viewerToken, get("/api/follow-ups"), null, 200);
		assertThat(followUpRows()).isEqualTo(1);
	}

	@Test
	void anIncidentListsItsFollowUpsOldestFirst() throws Exception {
		create(engineerToken, incidentId, "First", "CORRECTIVE", engineer.getId(), "2026-11-30", 201);
		holdClockAt(NOW.plusSeconds(60));
		create(engineerToken, incidentId, "Second", "PREVENTIVE", engineer.getId(), "2026-11-16", 201);

		assertThat(JsonPath.<List<String>>read(listForIncident(), "$[*].title")).containsExactly("First", "Second");
	}

	@Test
	void followUpsMayBeAddedAndStayOpenAfterTheIncidentCloses() throws Exception {
		jdbc.update("UPDATE incident SET status = 'CLOSED' WHERE id = ?", incidentId);
		long versionBefore = incidentVersion();

		String created = create(engineerToken, incidentId, "After closure", "PREVENTIVE", engineer.getId(), "2026-11-20", 201);

		assertThat(JsonPath.<String>read(created, "$.status")).isEqualTo("OPEN");
		// Like a comment, a follow-up is a row of its own: the incident's version does not move.
		assertThat(incidentVersion()).isEqualTo(versionBefore);
	}

	@Test
	void theOrgListFiltersByOwnerStatusAndOverdue() throws Exception {
		Incident other = incident(880_002L);
		long mineOverdue = idOf(create(engineerToken, incidentId, "Mine, overdue", "CORRECTIVE", engineer.getId(), "2026-11-15", 201));
		long mineLater = idOf(create(engineerToken, other.getId(), "Mine, later", "PREVENTIVE", engineer.getId(), "2026-12-10", 201));
		long mineDone = idOf(create(engineerToken, incidentId, "Mine, done", "CORRECTIVE", engineer.getId(), "2026-11-15", 201));
		long theirs = idOf(create(engineerToken, other.getId(), "Theirs, overdue", "CORRECTIVE", admin.getId(), "2026-11-16", 201));
		update(engineerToken, mineDone, "{\"status\":\"DONE\"}", 200);
		holdClockAt(Instant.parse("2026-11-20T08:00:00Z"));

		assertThat(ids(list(engineerToken, ""))).containsExactly(mineOverdue, mineDone, theirs, mineLater);
		assertThat(ids(list(engineerToken, "?owner=me"))).containsExactly(mineOverdue, mineDone, mineLater);
		assertThat(ids(list(adminToken, "?owner=" + engineer.getId()))).containsExactly(mineOverdue, mineDone, mineLater);
		assertThat(ids(list(engineerToken, "?owner=me&status=OPEN"))).containsExactly(mineOverdue, mineLater);
		assertThat(ids(list(engineerToken, "?owner=me&status=OPEN&overdue=true"))).containsExactly(mineOverdue);
		assertThat(ids(list(engineerToken, "?overdue=true"))).containsExactly(mineOverdue, theirs);
		assertThat(ids(list(engineerToken, "?overdue=false"))).containsExactly(mineDone, mineLater);
		assertThat(ids(list(engineerToken, "?status=DONE"))).containsExactly(mineDone);

		String across = list(engineerToken, "?owner=me&overdue=false");
		assertThat(JsonPath.<List<String>>read(across, "$[*].incidentReference")).containsExactly("INC-880001", "INC-880002");

		send(engineerToken, get("/api/follow-ups?owner=someone"), null, 400);
		send(engineerToken, get("/api/follow-ups?status=FINISHED"), null, 400);
	}

	private Incident incident(long number) {
		Incident unsaved = new Incident("Pool exhausted " + number, "Checkout errors", service, Severity.SEV2,
				IncidentStatus.INVESTIGATING, admin, clock.instant(), 60, 480);
		unsaved.assignNumber(number);
		return incidents.saveAndFlush(unsaved);
	}

	private void holdClockAt(Instant instant) {
		ReflectionTestUtils.setField(followUpService, "clock", Clock.fixed(instant, ZoneOffset.UTC));
	}

	private String create(String token, long incident, String title, String kind, long ownerId, String dueDate, int expected)
			throws Exception {
		return send(token, post("/api/incidents/{id}/follow-ups", incident),
				"{\"title\":\"%s\",\"kind\":\"%s\",\"ownerId\":%d,\"dueDate\":\"%s\"}".formatted(title, kind, ownerId, dueDate),
				expected);
	}

	private String update(String token, long id, String body, int expected) throws Exception {
		return send(token, put("/api/follow-ups/{id}", id), body, expected);
	}

	private String listForIncident() throws Exception {
		return send(viewerToken, get("/api/incidents/{id}/follow-ups", incidentId), null, 200);
	}

	private String list(String token, String query) throws Exception {
		return send(token, get("/api/follow-ups" + query), null, 200);
	}

	private static List<Long> ids(String json) {
		return JsonPath.<List<Number>>read(json, "$[*].id").stream().map(Number::longValue).toList();
	}

	private List<TimelineEntry> entries(TimelineEventType type) {
		return timeline.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId).stream()
				.filter(entry -> entry.getEventType() == type)
				.toList();
	}

	private Integer followUpRows() {
		return jdbc.queryForObject("SELECT count(*) FROM follow_up WHERE incident_id = ?", Integer.class, incidentId);
	}

	private long incidentVersion() {
		return jdbc.queryForObject("SELECT version FROM incident WHERE id = ?", Long.class, incidentId);
	}

	private String send(String token, MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
	}

	private static long idOf(String json) {
		return JsonPath.<Number>read(json, "$.id").longValue();
	}
}
