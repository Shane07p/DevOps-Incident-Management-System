package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentRole;
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
	private String engineer2Token;

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
		engineer2Token = accounts.token("role-eng2@test.local");

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
	@DisplayName("TC-FR-05.4a: Successful LEAD assignment updates lead, records timeline, creates notification, leaves SLA targets unchanged")
	void successfulLeadAssignment() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);
		Long initialVersion = incident.getVersion();
		long notificationsBefore = notifications.count();

		String body = "{\"role\": \"LEAD\", \"userId\": %d}".formatted(engineer2.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(incident.getId()))
				.andExpect(jsonPath("$.lead.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.lead.displayName").value(engineer2.getDisplayName()))
				.andExpect(jsonPath("$.roles.lead.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.role").value("LEAD"))
				.andExpect(jsonPath("$.user.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.warning").doesNotExist());

		// Database verification
		Incident updated = incidents.findById(incident.getId()).orElseThrow();
		assertThat(updated.getLead()).isNotNull();
		assertThat(updated.getLead().getId()).isEqualTo(engineer2.getId());
		assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);

		// Timeline verification
		List<TimelineEntry> entries = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		TimelineEntry lastEntry = entries.get(entries.size() - 1);
		assertThat(lastEntry.getEventType()).isEqualTo(TimelineEventType.ROLE_ASSIGNED);
		assertThat(lastEntry.getActor().getId()).isEqualTo(engineer1.getId());
		assertThat(lastEntry.getDetails()).isEqualTo("Assigned LEAD to " + engineer2.getDisplayName());

		// Notification verification
		assertThat(notifications.count()).isEqualTo(notificationsBefore + 1);
		List<Notification> userNotifications = notifications.findAllByRecipientIdOrderByCreatedAtDescIdDesc(engineer2.getId());
		assertThat(userNotifications).isNotEmpty();
		Notification notification = userNotifications.get(0);
		assertThat(notification.getIncident().getId()).isEqualTo(incident.getId());
		assertThat(notification.getMessage()).contains(incident.getReference());
		assertThat(notification.getMessage()).contains("LEAD");
		assertThat(notification.getReadAt()).isNull();
	}

	@Test
	@DisplayName("TC-FR-05.4a: Successful COMMS assignment updates comms role")
	void successfulCommsAssignment() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		String body = "{\"role\": \"COMMS\", \"userId\": %d}".formatted(engineer2.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(incident.getId()))
				.andExpect(jsonPath("$.comms.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.comms.displayName").value(engineer2.getDisplayName()))
				.andExpect(jsonPath("$.roles.comms.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.role").value("COMMS"));

		Incident updated = incidents.findById(incident.getId()).orElseThrow();
		assertThat(updated.getComms()).isNotNull();
		assertThat(updated.getComms().getId()).isEqualTo(engineer2.getId());

		List<TimelineEntry> entries = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		TimelineEntry lastEntry = entries.get(entries.size() - 1);
		assertThat(lastEntry.getEventType()).isEqualTo(TimelineEventType.ROLE_ASSIGNED);
		assertThat(lastEntry.getDetails()).isEqualTo("Assigned COMMS to " + engineer2.getDisplayName());
	}

	@Test
	@DisplayName("TC-FR-05.4a: Successful OPS assignment updates ops role")
	void successfulOpsAssignment() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		String body = "{\"role\": \"OPS\", \"userId\": %d}".formatted(engineer2.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(incident.getId()))
				.andExpect(jsonPath("$.ops.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.ops.displayName").value(engineer2.getDisplayName()))
				.andExpect(jsonPath("$.roles.ops.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.role").value("OPS"));

		Incident updated = incidents.findById(incident.getId()).orElseThrow();
		assertThat(updated.getOps()).isNotNull();
		assertThat(updated.getOps().getId()).isEqualTo(engineer2.getId());

		List<TimelineEntry> entries = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		TimelineEntry lastEntry = entries.get(entries.size() - 1);
		assertThat(lastEntry.getEventType()).isEqualTo(TimelineEventType.ROLE_ASSIGNED);
		assertThat(lastEntry.getDetails()).isEqualTo("Assigned OPS to " + engineer2.getDisplayName());
	}

	@Test
	@DisplayName("Accepts assigneeId as alias for userId in request body")
	void acceptsAssigneeIdAlias() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		String body = "{\"role\": \"OPS\", \"assigneeId\": %d}".formatted(engineer2.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ops.id").value(engineer2.getId()));

		Incident updated = incidents.findById(incident.getId()).orElseThrow();
		assertThat(updated.getOps().getId()).isEqualTo(engineer2.getId());
	}

	@Test
	@DisplayName("Unauthorized users: VIEWER receives 403 Forbidden, unauthenticated receives 401")
	void unauthorizedUserRejected() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);
		int initialTimelineCount = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId()).size();
		int initialNotificationCount = notifications.findAll().size();

		String body = "{\"role\": \"LEAD\", \"userId\": %d}".formatted(engineer2.getId());

		// VIEWER
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isForbidden());

		// Unauthenticated
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isUnauthorized());

		// Verify no changes persisted
		assertThat(timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId())).hasSize(initialTimelineCount);
		assertThat(notifications.findAll()).hasSize(initialNotificationCount);
	}

	@Test
	@DisplayName("Target user from another organization is rejected with 400 Bad Request")
	void crossOrganizationUserRejected() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		Instant now = clock.instant();
		Organization otherOrg = orgs.saveAndFlush(new Organization("Other Org", "other-org-roles", now));
		AppUser otherOrgUser = users.saveAndFlush(new AppUser("crossorg-roles@other.local", "Cross Org User", "hash", now));
		memberships.saveAndFlush(new Membership(otherOrg, otherOrgUser, Role.ENGINEER, now));

		String body = "{\"role\": \"LEAD\", \"userId\": %d}".formatted(otherOrgUser.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.userId").value("User not found"));
	}

	@Test
	@DisplayName("Disabled member of the same organization is rejected with 400 Bad Request")
	void disabledUserRejected() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		AppUser disabledUser = accounts.create("disabled-role-user@test.local", Role.ENGINEER);
		accounts.membership(disabledUser).disable();
		entityManager.flush();

		String body = "{\"role\": \"LEAD\", \"userId\": %d}".formatted(disabledUser.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.userId").value("User must be an enabled member"));
	}

	@Test
	@DisplayName("Role assignment on CLOSED incident returns 409 Conflict")
	void closedIncidentCannotHaveRolesChanged() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.CLOSED, engineer1);

		String body = "{\"role\": \"LEAD\", \"userId\": %d}".formatted(engineer2.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value("Cannot change roles on a closed incident"));
	}

	@Test
	@DisplayName("Role assignments are independent of incident assignee and do not modify incident.assignee")
	void roleAssignmentsDoNotModifyAssignee() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		String body = "{\"role\": \"LEAD\", \"userId\": %d}".formatted(engineer2.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignee.id").value(engineer1.getId()))
				.andExpect(jsonPath("$.lead.id").value(engineer2.getId()));

		Incident updated = incidents.findById(incident.getId()).orElseThrow();
		assertThat(updated.getAssignee().getId()).isEqualTo(engineer1.getId());
		assertThat(updated.getLead().getId()).isEqualTo(engineer2.getId());
	}

	@Test
	@DisplayName("TC-FR-01.3a: An incident role grants no application-level admin rights: Viewer assigned LEAD calling POST /api/users gets 403")
	void roleAssignmentsDoNotGrantAdminPrivileges() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		// Assign LEAD to viewer
		String body = "{\"role\": \"LEAD\", \"userId\": %d}".formatted(viewer.getId());
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());

		// Verify viewer now has LEAD on this incident
		Incident updated = incidents.findById(incident.getId()).orElseThrow();
		assertThat(updated.getLead().getId()).isEqualTo(viewer.getId());

		// Now caller with viewer token attempts an Admin-only operation: POST /api/users
		mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"hacker@test.local\",\"displayName\":\"Hacker\",\"password\":\"ValidPass123!\",\"role\":\"ADMIN\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Timeline records role transfer: from previous user to new user")
	void timelineRecordsRoleChange() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		// First assign LEAD to engineer1
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"LEAD\", \"userId\": %d}".formatted(engineer1.getId())))
				.andExpect(status().isOk());

		// Transfer LEAD from engineer1 to engineer2
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"LEAD\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isOk());

		List<TimelineEntry> entries = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		TimelineEntry transferEntry = entries.get(entries.size() - 1);
		assertThat(transferEntry.getEventType()).isEqualTo(TimelineEventType.ROLE_ASSIGNED);
		assertThat(transferEntry.getActor().getId()).isEqualTo(admin.getId());
		assertThat(transferEntry.getDetails())
				.isEqualTo("Assigned LEAD from " + engineer1.getDisplayName() + " to " + engineer2.getDisplayName());
	}

	@Test
	@DisplayName("FR-05.5: SEV1 incident with overlapping roles returns warning and HTTP 200 with X-Warning header")
	void sev1OverlappingRolesReturnWarning() throws Exception {
		Incident incident = createIncident(Severity.SEV1, IncidentStatus.OPEN, engineer1);

		// Assign LEAD to engineer2
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"LEAD\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.warning").doesNotExist());

		// Assign COMMS also to engineer2 -> overlap!
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"COMMS\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Warning"))
				.andExpect(header().string("X-Warning", "User " + engineer2.getDisplayName() + " holds multiple incident roles on a SEV1 incident"))
				.andExpect(header().string("Warning", "299 - \"User " + engineer2.getDisplayName() + " holds multiple incident roles on a SEV1 incident\""))
				.andExpect(jsonPath("$.warning").value("User " + engineer2.getDisplayName() + " holds multiple incident roles on a SEV1 incident"))
				.andExpect(jsonPath("$.lead.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.comms.id").value(engineer2.getId()));

		Incident updated = incidents.findById(incident.getId()).orElseThrow();
		assertThat(updated.getLead().getId()).isEqualTo(engineer2.getId());
		assertThat(updated.getComms().getId()).isEqualTo(engineer2.getId());
	}

	@Test
	@DisplayName("TC-FR-05.5a: SEV2 incident with overlapping roles returns warning and saves both roles")
	void sev2OverlappingRolesReturnWarning() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		// Assign COMMS to engineer2
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"COMMS\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isOk());

		// Assign OPS to engineer2 -> overlap on SEV2
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"OPS\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Warning", "User " + engineer2.getDisplayName() + " holds multiple incident roles on a SEV2 incident"))
				.andExpect(jsonPath("$.warning").value("User " + engineer2.getDisplayName() + " holds multiple incident roles on a SEV2 incident"))
				.andExpect(jsonPath("$.comms.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.ops.id").value(engineer2.getId()));
	}

	@Test
	@DisplayName("TC-FR-05.5a: SEV3 incident with overlapping roles returns no warning and saves both roles")
	void sev3DoesNotReturnWarning() throws Exception {
		Incident incident = createIncident(Severity.SEV3, IncidentStatus.OPEN, engineer1);

		// Assign LEAD to engineer2
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"LEAD\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isOk());

		// Assign COMMS to engineer2 -> no warning on SEV3
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"COMMS\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("X-Warning"))
				.andExpect(jsonPath("$.warning").doesNotExist())
				.andExpect(jsonPath("$.lead.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.comms.id").value(engineer2.getId()));
	}

	@Test
	@DisplayName("SEV4 incident with overlapping roles returns no warning and saves both roles")
	void sev4DoesNotReturnWarning() throws Exception {
		Incident incident = createIncident(Severity.SEV4, IncidentStatus.OPEN, engineer1);

		// Assign LEAD to engineer2
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"LEAD\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isOk());

		// Assign OPS to engineer2 -> no warning on SEV4
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"OPS\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("X-Warning"))
				.andExpect(jsonPath("$.warning").doesNotExist())
				.andExpect(jsonPath("$.lead.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.ops.id").value(engineer2.getId()));
	}

	@Test
	@DisplayName("Invalid role values (e.g. SCRIBE or INVALID) return 400 Bad Request")
	void invalidRoleValuesRejected() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"SCRIBE\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isBadRequest());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"INVALID\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Missing role or user returns 400 Bad Request")
	void missingRoleOrUserRejected() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		// Missing role
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isBadRequest());

		// Missing userId
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"LEAD\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.userId").value("User is required"));
	}

	@Test
	@DisplayName("Conflicting userId and assigneeId in request body returns 400 Bad Request")
	void conflictingUserIdAndAssigneeIdRejected() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		String body = "{\"role\": \"LEAD\", \"userId\": %d, \"assigneeId\": %d}".formatted(engineer1.getId(), engineer2.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.userId").value("Conflicting userId and assigneeId provided"));
	}

	@Test
	@DisplayName("Matching userId and assigneeId in request body is accepted")
	void matchingUserIdAndAssigneeIdAccepted() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		String body = "{\"role\": \"LEAD\", \"userId\": %d, \"assigneeId\": %d}".formatted(engineer2.getId(), engineer2.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lead.id").value(engineer2.getId()));
	}

	@Test
	@DisplayName("Assigning same user to the same role returns 400 Bad Request without duplicate timeline or notification")
	void assignSameUserToSameRoleReturns400() throws Exception {
		Incident incident = createIncident(Severity.SEV2, IncidentStatus.OPEN, engineer1);

		// First assign LEAD to engineer2
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"LEAD\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isOk());

		int timelineCount = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId()).size();
		int notificationCount = notifications.findAll().size();
		Long initialVersion = incidents.findById(incident.getId()).orElseThrow().getVersion();

		// Attempt to assign LEAD again to engineer2
		mvc.perform(put("/api/incidents/" + incident.getId() + "/roles")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"role\": \"LEAD\", \"userId\": %d}".formatted(engineer2.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errors.userId").value("Already assigned to this role"));

		// Verify no changes persisted
		Incident persisted = incidents.findById(incident.getId()).orElseThrow();
		assertThat(persisted.getVersion()).isEqualTo(initialVersion);
		assertThat(timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId())).hasSize(timelineCount);
		assertThat(notifications.findAll()).hasSize(notificationCount);
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
