package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
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
import com.group2.incident.notification.entity.Notification;
import com.group2.incident.notification.repository.NotificationRepository;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.entity.Organization;
import com.group2.incident.org.repository.MembershipRepository;
import com.group2.incident.org.repository.OrganizationRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Incident roles (D39): the Lead is the assignee; Comms and Ops are given through PUT /roles. */
@IntegrationTest
class IncidentRoleTest {

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
	private NotificationRepository notifications;

	@Autowired
	private MembershipRepository memberships;

	@Autowired
	private OrganizationRepository orgs;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private Clock clock;

	private AppUser admin;
	private String adminToken;

	private AppUser engineer1;
	private String engineer1Token;

	private AppUser engineer2;

	private AppUser viewer;
	private String viewerToken;

	private ServiceEntity paymentsService;

	private long incidentSequence = 900L;

	@BeforeEach
	void setUp() throws Exception {
		admin = accounts.create("role-admin@test.local", Role.ADMIN);
		adminToken = accounts.token("role-admin@test.local");

		engineer1 = accounts.create("role-eng1@test.local", Role.ENGINEER);
		engineer1Token = accounts.token("role-eng1@test.local");

		engineer2 = accounts.create("role-eng2@test.local", Role.ENGINEER);

		viewer = accounts.create("role-viewer@test.local", Role.VIEWER);
		viewerToken = accounts.token("role-viewer@test.local");

		Instant now = clock.instant();
		ServiceEntity service = new ServiceEntity("Role Payments", "Payments service for roles", admin, Criticality.HIGH, now);
		service.putTarget(Severity.SEV1, 15, 240);
		service.putTarget(Severity.SEV2, 60, 480);
		service.putTarget(Severity.SEV3, 240, 2880);
		service.putTarget(Severity.SEV4, 1440, 10080);
		paymentsService = services.saveAndFlush(service);
	}

	@Test
	@DisplayName("TC-FR-05.4a: giving Comms saves it, records the timeline, notifies the person, and keeps the SLA targets")
	void successfulCommsAssignment() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);
		Long initialVersion = incident.getVersion();
		long notificationsBefore = notifications.count();

		giveRole(incident, "COMMS", engineer2, engineer1Token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(incident.getId()))
				.andExpect(jsonPath("$.version").value(initialVersion + 1))
				.andExpect(jsonPath("$.assignee.id").value(engineer1.getId()))
				.andExpect(jsonPath("$.roles.comms.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.roles.comms.displayName").value(engineer2.getDisplayName()))
				.andExpect(jsonPath("$.roles.ops").doesNotExist())
				.andExpect(jsonPath("$.roles.lead").doesNotExist())
				.andExpect(jsonPath("$.lead").doesNotExist())
				.andExpect(jsonPath("$.role").doesNotExist())
				.andExpect(jsonPath("$.user").doesNotExist())
				.andExpect(jsonPath("$.roleWarning").doesNotExist())
				.andExpect(jsonPath("$.responseTargetMinutes").value(60))
				.andExpect(jsonPath("$.resolutionTargetMinutes").value(480));

		Incident updated = incidents.findById(incident.getId()).orElseThrow();
		assertThat(updated.getComms().getId()).isEqualTo(engineer2.getId());
		assertThat(updated.getAssignee().getId()).isEqualTo(engineer1.getId());
		assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);

		TimelineEntry lastEntry = lastTimelineEntry(incident);
		assertThat(lastEntry.getEventType()).isEqualTo(TimelineEventType.ROLE_ASSIGNED);
		assertThat(lastEntry.getActor().getId()).isEqualTo(engineer1.getId());
		assertThat(lastEntry.getDetails()).isEqualTo("Assigned COMMS to " + engineer2.getDisplayName());

		assertThat(notifications.count()).isEqualTo(notificationsBefore + 1);
		List<Notification> received = notifications.findAllByRecipientIdOrderByCreatedAtDescIdDesc(engineer2.getId());
		assertThat(received).isNotEmpty();
		Notification notification = received.get(0);
		assertThat(notification.getIncident().getId()).isEqualTo(incident.getId());
		assertThat(notification.getMessage()).contains(incident.getReference()).contains("COMMS");
		assertThat(notification.getReadAt()).isNull();
	}

	@Test
	@DisplayName("TC-FR-05.4a: giving Ops saves it and records the timeline")
	void successfulOpsAssignment() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		giveRole(incident, "OPS", engineer2, adminToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roles.ops.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.roles.ops.displayName").value(engineer2.getDisplayName()));

		Incident updated = incidents.findById(incident.getId()).orElseThrow();
		assertThat(updated.getOps().getId()).isEqualTo(engineer2.getId());

		TimelineEntry lastEntry = lastTimelineEntry(incident);
		assertThat(lastEntry.getEventType()).isEqualTo(TimelineEventType.ROLE_ASSIGNED);
		assertThat(lastEntry.getDetails()).isEqualTo("Assigned OPS to " + engineer2.getDisplayName());
	}

	@Test
	@DisplayName("TC-FR-05.4a: the transfer of a role shows who held it before")
	void timelineRecordsRoleTransfer() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		giveRole(incident, "COMMS", engineer1, adminToken).andExpect(status().isOk());
		giveRole(incident, "COMMS", engineer2, adminToken).andExpect(status().isOk());

		TimelineEntry transfer = lastTimelineEntry(incident);
		assertThat(transfer.getEventType()).isEqualTo(TimelineEventType.ROLE_ASSIGNED);
		assertThat(transfer.getActor().getId()).isEqualTo(admin.getId());
		assertThat(transfer.getDetails())
				.isEqualTo("Assigned COMMS from " + engineer1.getDisplayName() + " to " + engineer2.getDisplayName());
	}

	@Test
	@DisplayName("D39: the Lead is the assignee, so PUT /roles refuses LEAD and points to PUT /assignee")
	void leadIsChangedThroughTheAssignee() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);
		int timelineBefore = timeline(incident).size();
		long notificationsBefore = notifications.count();

		giveRole(incident, "LEAD", engineer2, adminToken)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.role")
						.value("The Lead is the assignee: change it with PUT /api/incidents/" + incident.getId() + "/assignee"));

		Incident unchanged = incidents.findById(incident.getId()).orElseThrow();
		assertThat(unchanged.getAssignee().getId()).isEqualTo(engineer1.getId());
		assertThat(unchanged.getVersion()).isEqualTo(incident.getVersion());
		assertThat(timeline(incident)).hasSize(timelineBefore);
		assertThat(notifications.count()).isEqualTo(notificationsBefore);
	}

	@Test
	@DisplayName("A Viewer gets 403 and an anonymous caller 401; nothing is saved")
	void unauthorizedUserRejected() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);
		int timelineBefore = timeline(incident).size();
		long notificationsBefore = notifications.count();

		giveRole(incident, "COMMS", engineer2, viewerToken).andExpect(status().isForbidden());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.contentType(MediaType.APPLICATION_JSON)
						.content(roleBody("COMMS", engineer2, incident.getVersion())))
				.andExpect(status().isUnauthorized());

		assertThat(timeline(incident)).hasSize(timelineBefore);
		assertThat(notifications.count()).isEqualTo(notificationsBefore);
	}

	@Test
	@DisplayName("A person from another org is refused with 400")
	void crossOrganizationUserRejected() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		Instant now = clock.instant();
		Organization otherOrg = orgs.saveAndFlush(new Organization("Other Org", "other-org-roles", now));
		AppUser outsider = users.saveAndFlush(new AppUser("crossorg-roles@other.local", "Cross Org User", "hash", now));
		memberships.saveAndFlush(new Membership(otherOrg, outsider, Role.ENGINEER, now));

		giveRole(incident, "COMMS", outsider, engineer1Token)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.userId").value("User not found"));
	}

	@Test
	@DisplayName("A disabled member is refused with 400")
	void disabledUserRejected() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		AppUser disabledUser = accounts.create("disabled-role-user@test.local", Role.ENGINEER);
		accounts.membership(disabledUser).disable();
		entityManager.flush();

		giveRole(incident, "COMMS", disabledUser, engineer1Token)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.userId").value("User must be an enabled member"));
	}

	@Test
	@DisplayName("Roles on a CLOSED incident are refused with 409")
	void closedIncidentCannotHaveRolesChanged() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.CLOSED, engineer1);

		giveRole(incident, "COMMS", engineer2, engineer1Token)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Cannot change roles on a closed incident"));
	}

	@Test
	@DisplayName("TC-FR-01.3a: an incident role gives no admin rights: a Viewer with Comms still gets 403 from POST /api/users")
	void roleAssignmentsDoNotGrantAdminPrivileges() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		giveRole(incident, "COMMS", viewer, adminToken).andExpect(status().isOk());
		assertThat(incidents.findById(incident.getId()).orElseThrow().getComms().getId()).isEqualTo(viewer.getId());

		mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"hacker@test.local\",\"displayName\":\"Hacker\",\"password\":\"ValidPass123!\",\"role\":\"ADMIN\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("FR-05.5: on a SEV1, the Lead also taking Comms gets a warning in the body only, and the incident page keeps showing it")
	void sev1LeadAlsoCommsWarns() throws Exception {
		Incident incident = createIncident(Severity.SEV1, IncidentStatus.OPEN, engineer1);
		String warning = engineer1.getDisplayName() + " holds Lead and Comms on this SEV1 incident";

		giveRole(incident, "COMMS", engineer1, adminToken)
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("X-Warning"))
				.andExpect(header().doesNotExist("Warning"))
				.andExpect(jsonPath("$.roleWarning").value(warning))
				.andExpect(jsonPath("$.assignee.id").value(engineer1.getId()))
				.andExpect(jsonPath("$.roles.comms.id").value(engineer1.getId()));

		mvc.perform(get("/api/incidents/" + incident.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roleWarning").value(warning));
	}

	@Test
	@DisplayName("TC-FR-05.5a: on a SEV2, one person with Comms and Ops gets a warning, and both roles are saved")
	void sev2CommsAndOpsWarns() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		giveRole(incident, "COMMS", engineer2, engineer1Token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roleWarning").doesNotExist());

		giveRole(incident, "OPS", engineer2, engineer1Token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roleWarning").value(engineer2.getDisplayName() + " holds Comms and Ops on this SEV2 incident"))
				.andExpect(jsonPath("$.roles.comms.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.roles.ops.id").value(engineer2.getId()));
	}

	@Test
	@DisplayName("FR-05.5: one person holding all three roles is named with each of them")
	void allThreeRolesWarn() throws Exception {
		Incident incident = createIncident(Severity.SEV1, IncidentStatus.OPEN, engineer1);

		giveRole(incident, "COMMS", engineer1, adminToken).andExpect(status().isOk());
		giveRole(incident, "OPS", engineer1, adminToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roleWarning").value(engineer1.getDisplayName() + " holds Lead, Comms and Ops on this SEV1 incident"));
	}

	@Test
	@DisplayName("FR-05.5: reassigning the incident to the Comms holder makes them Lead too, and warns")
	void reassignToCommsHolderWarns() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);
		giveRole(incident, "COMMS", engineer2, adminToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roleWarning").doesNotExist());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"assigneeId\": %d, \"expectedVersion\": %d}".formatted(engineer2.getId(), currentVersion(incident))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignee.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.roleWarning").value(engineer2.getDisplayName() + " holds Lead and Comms on this SEV2 incident"));
	}

	@Test
	@DisplayName("TC-FR-05.5a: on a SEV3, the same overlap is saved without a warning")
	void sev3DoesNotWarn() throws Exception {
		Incident incident = createIncident(Severity.SEV3, IncidentStatus.OPEN, engineer1);

		giveRole(incident, "COMMS", engineer1, adminToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roleWarning").doesNotExist())
				.andExpect(jsonPath("$.roles.comms.id").value(engineer1.getId()));
	}

	@Test
	@DisplayName("TC-FR-05.5a: on a SEV4, the same overlap is saved without a warning")
	void sev4DoesNotWarn() throws Exception {
		Incident incident = createIncident(Severity.SEV4, IncidentStatus.OPEN, engineer1);

		giveRole(incident, "OPS", engineer1, adminToken)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roleWarning").doesNotExist())
				.andExpect(jsonPath("$.roles.ops.id").value(engineer1.getId()));
	}

	@Test
	@DisplayName("NFR-11.2: a role change made from an older view of the incident is refused with 409, not applied")
	void staleRoleChangeIsRefused() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);
		long seen = currentVersion(incident);

		// Another screen's change commits first.
		giveRole(incident, "OPS", engineer1, adminToken, seen).andExpect(status().isOk());

		giveRole(incident, "COMMS", engineer2, adminToken, seen)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("This record was changed by another request; reload and try again"));
		assertThat(incidents.findById(incident.getId()).orElseThrow().getComms()).isNull();
	}

	@Test
	@DisplayName("Unknown roles (SCRIBE, INVALID) are refused with 400")
	void invalidRoleValuesRejected() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		giveRole(incident, "SCRIBE", engineer2, engineer1Token).andExpect(status().isBadRequest());
		giveRole(incident, "INVALID", engineer2, engineer1Token).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("A missing role or user is refused with 400; assigneeId is not read as the user")
	void missingRoleOrUserRejected() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		sendRoleBody(incident, "{\"userId\": %d}".formatted(engineer2.getId()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.role").value("Role is required"));

		sendRoleBody(incident, "{\"role\": \"COMMS\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.userId").value("User is required"));

		sendRoleBody(incident, "{\"role\": \"COMMS\", \"assigneeId\": %d}".formatted(engineer2.getId()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.userId").value("User is required"));
	}

	@Test
	@DisplayName("Giving a role to the person who already holds it is refused with 400, with no timeline entry or notification")
	void assignSameUserToSameRoleReturns400() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);
		giveRole(incident, "COMMS", engineer2, adminToken).andExpect(status().isOk());

		int timelineBefore = timeline(incident).size();
		long notificationsBefore = notifications.count();
		Long versionBefore = incidents.findById(incident.getId()).orElseThrow().getVersion();

		giveRole(incident, "COMMS", engineer2, adminToken)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errors.userId").value("Already assigned to this role"));

		assertThat(incidents.findById(incident.getId()).orElseThrow().getVersion()).isEqualTo(versionBefore);
		assertThat(timeline(incident)).hasSize(timelineBefore);
		assertThat(notifications.count()).isEqualTo(notificationsBefore);
	}

	/** A role change made from the incident as it is now, as a screen that just loaded it would send. */
	private ResultActions giveRole(Incident incident, String role, AppUser person, String token) throws Exception {
		return giveRole(incident, role, person, token, currentVersion(incident));
	}

	private ResultActions giveRole(Incident incident, String role, AppUser person, String token, long expectedVersion) throws Exception {
		return mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(roleBody(role, person, expectedVersion)));
	}

	private long currentVersion(Incident incident) {
		return incidents.findById(incident.getId()).orElseThrow().getVersion();
	}

	private ResultActions sendRoleBody(Incident incident, String body) throws Exception {
		return mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private static String roleBody(String role, AppUser person, long expectedVersion) {
		return "{\"role\": \"%s\", \"userId\": %d, \"expectedVersion\": %d}".formatted(role, person.getId(), expectedVersion);
	}

	private List<TimelineEntry> timeline(Incident incident) {
		return timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
	}

	private TimelineEntry lastTimelineEntry(Incident incident) {
		List<TimelineEntry> entries = timeline(incident);
		return entries.get(entries.size() - 1);
	}

	private Incident createIncident(Severity severity, IncidentStatus status, AppUser assignee) {
		Instant now = clock.instant();
		Incident incident = new Incident(
				"Test Incident",
				"Description",
				paymentsService,
				severity,
				status,
				engineer1,
				now,
				60,
				480
		);
		if (assignee != null) {
			incident.assignTo(assignee);
		}
		incident.assignNumber(incidentSequence++);
		return incidents.saveAndFlush(incident);
	}
}
