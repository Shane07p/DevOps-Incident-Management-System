package com.group2.incident.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
public class ServiceControllerTest {

    private static final String PASSWORD = "DevPassw0rd!";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private ServiceRepository services;

    @Autowired
    private AuditEntryRepository auditEntries;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private AppUser admin;
    private AppUser engineer;
    private AppUser viewer;
    private String adminToken;
    private String engineerToken;
    private String viewerToken;

    @BeforeEach
    void setUp() throws Exception {
        admin = createUser("admin", Role.ADMIN);
        engineer = createUser("engineer", Role.ENGINEER);
        viewer = createUser("viewer", Role.VIEWER);

        adminToken = tokenFor("admin");
        engineerToken = tokenFor("engineer");
        viewerToken = tokenFor("viewer");
    }

    @Test
    void listReturnsAllServicesInAlphabeticalOrder() throws Exception {
        createService("Payments", admin.getId(), "CRITICAL", targets(15, 240));
        createService("Auth", admin.getId(), "HIGH", targets(15, 240));

        mvc.perform(get("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Auth"))
                .andExpect(jsonPath("$[1].name").value("Payments"));
    }

    @Test
    void getOneReturnsServiceDetails() throws Exception {
        long id = createService("Payments", admin.getId(), "CRITICAL", targets(15, 240));

        mvc.perform(get("/api/services/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Payments"))
                .andExpect(jsonPath("$.criticality").value("CRITICAL"))
                .andExpect(jsonPath("$.owner.id").value(admin.getId()))
                .andExpect(jsonPath("$.owner.displayName").value("Test admin"))
                .andExpect(jsonPath("$.targets.SEV1.responseMinutes").value(15))
                .andExpect(jsonPath("$.targets.SEV1.resolutionMinutes").value(240));
    }

    @Test
    void getUnknownServiceReturns400() throws Exception {
        mvc.perform(get("/api/services/999999").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.serviceId").value("Service not found"));
    }

    @Test
    void adminCreatesServiceWithAudit() throws Exception {
        String body = body("Checkout", admin.getId(), "HIGH", targets(10, 120));

        String location = mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("Checkout"))
                .andExpect(jsonPath("$.criticality").value("HIGH"))
                .andReturn().getResponse().getHeader("Location");

        long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        List<AuditEntry> auditList = auditEntries.findAll().stream()
                .filter(e -> e.getItemType() == AuditItemType.SERVICE && e.getItemId() == id)
                .toList();
        assertThat(auditList).hasSize(1);
        assertThat(auditList.get(0).getAction()).isEqualTo(AuditAction.SERVICE_CREATED);
        assertThat(auditList.get(0).getActor().getId()).isEqualTo(admin.getId());
    }

    @Test
    void missingFieldsAreRejected() throws Exception {
        String invalidBody = "{\"description\": \"missing name, owner, criticality, targets\"}";

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Name is required"))
                .andExpect(jsonPath("$.errors.ownerId").value("Owner is required"))
                .andExpect(jsonPath("$.errors.criticality").value("Criticality is required"))
                .andExpect(jsonPath("$.errors.targets").value("Targets are required"));
    }

    @Test
    void missingSeverityTargetIsRejected() throws Exception {
        String threeSeverities = "{\"SEV1\": {\"responseMinutes\": 10, \"resolutionMinutes\": 60},"
                + " \"SEV2\": {\"responseMinutes\": 10, \"resolutionMinutes\": 60},"
                + " \"SEV3\": {\"responseMinutes\": 10, \"resolutionMinutes\": 60}}";
        String body = body("Catalog", admin.getId(), "LOW", threeSeverities);

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['targets.SEV4']").value("Targets for SEV4 are required"));
    }

    @Test
    void resolutionShorterThanResponseIsRejected() throws Exception {
        String invalidTargets = "{\"SEV1\": {\"responseMinutes\": 60, \"resolutionMinutes\": 30},"
                + " \"SEV2\": {\"responseMinutes\": 60, \"resolutionMinutes\": 60},"
                + " \"SEV3\": {\"responseMinutes\": 60, \"resolutionMinutes\": 60},"
                + " \"SEV4\": {\"responseMinutes\": 60, \"resolutionMinutes\": 60}}";
        String body = body("Catalog", admin.getId(), "LOW", invalidTargets);

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['targets.SEV1.resolutionMinutes']")
                        .value("Resolution target can't be shorter than the response target"));
    }

    @Test
    void duplicateServiceNameIsRejected() throws Exception {
        createService("Billing", admin.getId(), "MEDIUM", targets(15, 240));

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("billing", admin.getId(), "LOW", targets(15, 240))))
                .andExpect(status().isConflict());
    }

    @Test
    void adminUpdatesService() throws Exception {
        long id = createService("Billing", admin.getId(), "MEDIUM", targets(15, 240));

        mvc.perform(put("/api/services/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Billing v2", engineer.getId(), "CRITICAL", targets(5, 120))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Billing v2"))
                .andExpect(jsonPath("$.criticality").value("CRITICAL"))
                .andExpect(jsonPath("$.owner.displayName").value("Test engineer"));

        List<AuditEntry> auditList = auditEntries.findAll().stream()
                .filter(e -> e.getItemType() == AuditItemType.SERVICE && e.getItemId() == id)
                .toList();
        assertThat(auditList).hasSize(2);
        assertThat(auditList.get(1).getAction()).isEqualTo(AuditAction.SERVICE_UPDATED);
        assertThat(auditList.get(1).getActor().getId()).isEqualTo(admin.getId());
    }

    @Test
    void engineerCannotCreateOrUpdate() throws Exception {
        long id = createService("Billing", admin.getId(), "MEDIUM", targets(15, 240));

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("New Service", admin.getId(), "LOW", targets(15, 240))))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/services/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Billing v2", admin.getId(), "LOW", targets(15, 240))))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotCreateOrUpdate() throws Exception {
        long id = createService("Billing", admin.getId(), "MEDIUM", targets(15, 240));

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("New Service", admin.getId(), "LOW", targets(15, 240))))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/services/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Billing v2", admin.getId(), "LOW", targets(15, 240))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCallReturns401() throws Exception {
        mvc.perform(get("/api/services")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private long createService(String name, Long ownerId, String criticality, String targets) throws Exception {
        String response = mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, ownerId, criticality, targets)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private AppUser createUser(String username, Role role) {
        return users.saveAndFlush(new AppUser(username, "Test " + username, passwordEncoder.encode(PASSWORD), role,
                clock.instant()));
    }

    private String tokenFor(String username) throws Exception {
        String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD);
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.token");
    }

    private static String body(String name, long ownerId, String criticality, String targets) {
        return """
                {"name": "%s", "description": "Test service", "ownerId": %d, "criticality": "%s", "targets": %s}
                """.formatted(name, ownerId, criticality, targets);
    }

    private static String targets(int response, int resolution) {
        String one = "{\"responseMinutes\": %d, \"resolutionMinutes\": %d}".formatted(response, resolution);
        return "{\"SEV1\": %s, \"SEV2\": %s, \"SEV3\": %s, \"SEV4\": %s}".formatted(one, one, one, one);
    }
}
