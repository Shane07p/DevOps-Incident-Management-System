package com.group2.incident.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.group2.incident.catalog.repository.ServiceDependencyRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ServiceDependencyControllerTest {

    private static final String PASSWORD = "DevPassw0rd!";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private ServiceRepository services;

    @Autowired
    private ServiceDependencyRepository dependencies;

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

    private Long serviceAId;
    private Long serviceBId;
    private Long serviceCId;
    private Long serviceDId;

    @BeforeEach
    void setUp() throws Exception {
        dependencies.deleteAll();
        auditEntries.deleteAll();
        services.deleteAll();
        users.deleteAll();

        admin = users.saveAndFlush(new AppUser("admin", "Admin User", passwordEncoder.encode(PASSWORD), Role.ADMIN, clock.instant()));
        engineer = users.saveAndFlush(new AppUser("engineer", "Engineer User", passwordEncoder.encode(PASSWORD), Role.ENGINEER, clock.instant()));
        viewer = users.saveAndFlush(new AppUser("viewer", "Viewer User", passwordEncoder.encode(PASSWORD), Role.VIEWER, clock.instant()));

        adminToken = tokenFor("admin");
        engineerToken = tokenFor("engineer");
        viewerToken = tokenFor("viewer");

        serviceAId = createService("Auth Service", Criticality.CRITICAL);
        serviceBId = createService("Billing Service", Criticality.HIGH);
        serviceCId = createService("Cart Service", Criticality.MEDIUM);
        serviceDId = createService("Delivery Service", Criticality.LOW);
    }

    private Long createService(String name, Criticality criticality) {
        ServiceEntity entity = new ServiceEntity(name, name + " description", admin, criticality, clock.instant());
        entity.putTarget(Severity.SEV1, 15, 240);
        entity.putTarget(Severity.SEV2, 60, 480);
        entity.putTarget(Severity.SEV3, 240, 2880);
        entity.putTarget(Severity.SEV4, 1440, 10080);
        return services.saveAndFlush(entity).getId();
    }

    private String tokenFor(String username) throws Exception {
        String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD);
        String response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.token");
    }

    @Test
    @DisplayName("Admin successfully replaces dependencies")
    void adminSuccessfullyReplacesDependencies() throws Exception {
        mvc.perform(put("/api/services/" + serviceBId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(serviceBId))
                .andExpect(jsonPath("$.dependsOn", hasSize(1)))
                .andExpect(jsonPath("$.dependsOn[0]").value(serviceAId));

        List<Long> deps = dependencies.findDependsOnIdsByServiceId(serviceBId);
        assertThat(deps).containsExactly(serviceAId);
    }

    @Test
    @DisplayName("Replacing dependencies removes previous links that are no longer submitted")
    void replacingDependenciesRemovesPreviousLinks() throws Exception {
        // Initial setup: B depends on A and C
        mvc.perform(put("/api/services/" + serviceBId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + ", " + serviceCId + "]}"))
                .andExpect(status().isOk());

        assertThat(dependencies.findDependsOnIdsByServiceId(serviceBId)).containsExactly(serviceAId, serviceCId);

        // Replace: B depends only on C
        mvc.perform(put("/api/services/" + serviceBId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceCId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependsOn", hasSize(1)))
                .andExpect(jsonPath("$.dependsOn[0]").value(serviceCId));

        assertThat(dependencies.findDependsOnIdsByServiceId(serviceBId)).containsExactly(serviceCId);
    }

    @Test
    @DisplayName("Self-dependency returns HTTP 400")
    void selfDependencyReturns400() throws Exception {
        mvc.perform(put("/api/services/" + serviceAId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dependsOn").exists());
    }

    @Test
    @DisplayName("Referenced nonexistent service ID returns HTTP 400")
    void nonexistentReferencedServiceIdReturns400() throws Exception {
        mvc.perform(put("/api/services/" + serviceAId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [999999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dependsOn").exists());
    }

    @Test
    @DisplayName("Nonexistent target service returns HTTP 404")
    void nonexistentTargetServiceReturns404() throws Exception {
        mvc.perform(put("/api/services/999999/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + "]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Successful dependency changes are audited; no-op update is not audited")
    void dependencyChangesAreAuditedAndNoopSkipped() throws Exception {
        assertThat(auditEntries.count()).isZero();

        // 1. Change dependencies: B -> A
        mvc.perform(put("/api/services/" + serviceBId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + "]}"))
                .andExpect(status().isOk());

        List<AuditEntry> entries = auditEntries.findAll();
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.getAction()).isEqualTo(AuditAction.SERVICE_DEPENDENCIES_UPDATED);
        assertThat(entry.getItemType()).isEqualTo(AuditItemType.SERVICE);
        assertThat(entry.getItemId()).isEqualTo(serviceBId);
        assertThat(entry.getActor().getId()).isEqualTo(admin.getId());
        assertThat(entry.getDetails()).contains("dependencies:");

        // 2. Submit the same dependencies: no new audit record
        mvc.perform(put("/api/services/" + serviceBId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + "]}"))
                .andExpect(status().isOk());

        assertThat(auditEntries.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Duplicates in requested dependencies are deduplicated")
    void duplicateDependenciesAreDeduplicated() throws Exception {
        mvc.perform(put("/api/services/" + serviceBId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + ", " + serviceAId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependsOn", hasSize(1)))
                .andExpect(jsonPath("$.dependsOn[0]").value(serviceAId));

        List<Long> deps = dependencies.findDependsOnIdsByServiceId(serviceBId);
        assertThat(deps).containsExactly(serviceAId);
    }

    @Test
    @DisplayName("Service map returns nodes with id, name, criticality, and health UNKNOWN")
    void serviceMapReturnsNodesWithExpectedFields() throws Exception {
        mvc.perform(get("/api/service-map")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(4)))
                .andExpect(jsonPath("$.nodes[0].id").exists())
                .andExpect(jsonPath("$.nodes[0].name").exists())
                .andExpect(jsonPath("$.nodes[0].criticality").exists())
                .andExpect(jsonPath("$.nodes[0].health").value("UNKNOWN"))
                .andExpect(jsonPath("$.nodes[1].health").value("UNKNOWN"))
                .andExpect(jsonPath("$.nodes[2].health").value("UNKNOWN"))
                .andExpect(jsonPath("$.nodes[3].health").value("UNKNOWN"));
    }

    @Test
    @DisplayName("Service map returns edges with direction from dependent to dependency")
    void serviceMapReturnsEdgesWithCorrectDirection() throws Exception {
        // B depends on A: from = B, to = A
        mvc.perform(put("/api/services/" + serviceBId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + "]}"))
                .andExpect(status().isOk());

        // C depends on B: from = C, to = B
        mvc.perform(put("/api/services/" + serviceCId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceBId + "]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/service-map")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edges", hasSize(2)))
                .andExpect(jsonPath("$.edges[0].from").value(serviceBId))
                .andExpect(jsonPath("$.edges[0].to").value(serviceAId))
                .andExpect(jsonPath("$.edges[1].from").value(serviceCId))
                .andExpect(jsonPath("$.edges[1].to").value(serviceBId));
    }

    @Test
    @DisplayName("Blast radius returns direct dependents")
    void blastRadiusDirectDependents() throws Exception {
        // B depends on A
        mvc.perform(put("/api/services/" + serviceBId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + "]}"))
                .andExpect(status().isOk());

        // Blast radius for A should return B
        mvc.perform(get("/api/services/" + serviceAId + "/blast-radius")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(serviceBId))
                .andExpect(jsonPath("$[0].name").value("Billing Service"));
    }

    @Test
    @DisplayName("Blast radius returns transitive dependents across multiple levels")
    void blastRadiusTransitiveDependents() throws Exception {
        // C depends on B, B depends on A
        mvc.perform(put("/api/services/" + serviceBId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + "]}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/services/" + serviceCId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceBId + "]}"))
                .andExpect(status().isOk());

        // Blast radius for A should return B and C
        mvc.perform(get("/api/services/" + serviceAId + "/blast-radius")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id == " + serviceBId + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + serviceCId + ")]").exists());
    }

    @Test
    @DisplayName("Blast radius handles cycles safely without infinite loop and excludes target")
    void blastRadiusHandlesCyclesSafely() throws Exception {
        // Cycle: A depends on B, B depends on C, C depends on A
        mvc.perform(put("/api/services/" + serviceAId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceBId + "]}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/services/" + serviceBId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceCId + "]}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/services/" + serviceCId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + "]}"))
                .andExpect(status().isOk());

        // Target A: who depends on A? C depends on A. Who depends on C? B depends on C.
        // Who depends on B? A depends on B (cycle back to target).
        // Must return B and C, terminate, and NOT include target A.
        mvc.perform(get("/api/services/" + serviceAId + "/blast-radius")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id == " + serviceBId + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + serviceCId + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + serviceAId + ")]").doesNotExist());
    }

    @Test
    @DisplayName("Blast radius returns each affected service at most once on multiple dependency paths")
    void blastRadiusReturnsEachAffectedServiceAtMostOnce() throws Exception {
        // Diamond: D depends on B and C; B and C both depend on A
        mvc.perform(put("/api/services/" + serviceBId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + "]}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/services/" + serviceCId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceAId + "]}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/services/" + serviceDId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": [" + serviceBId + ", " + serviceCId + "]}"))
                .andExpect(status().isOk());

        // Blast radius for A should return B, C, D (each exactly once, total 3)
        mvc.perform(get("/api/services/" + serviceAId + "/blast-radius")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.id == " + serviceBId + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + serviceCId + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + serviceDId + ")]").exists());
    }

    @Test
    @DisplayName("Blast radius returns empty list when service has no dependents")
    void blastRadiusReturnsEmptyListWhenNoDependents() throws Exception {
        mvc.perform(get("/api/services/" + serviceDId + "/blast-radius")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("Blast radius returns HTTP 404 for nonexistent service")
    void blastRadiusNonexistentServiceReturns404() throws Exception {
        mvc.perform(get("/api/services/999999/blast-radius")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Engineer and Viewer are forbidden (403), unauthenticated receives 401")
    void rolePermissionsVerification() throws Exception {
        // PUT /api/services/{id}/dependencies
        mvc.perform(put("/api/services/" + serviceAId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": []}"))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/services/" + serviceAId + "/dependencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": []}"))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/services/" + serviceAId + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\": []}"))
                .andExpect(status().isUnauthorized());

        // GET /api/service-map
        mvc.perform(get("/api/service-map")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/service-map")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/service-map"))
                .andExpect(status().isUnauthorized());

        // GET /api/services/{id}/blast-radius
        mvc.perform(get("/api/services/" + serviceAId + "/blast-radius")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/services/" + serviceAId + "/blast-radius")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/services/" + serviceAId + "/blast-radius"))
                .andExpect(status().isUnauthorized());
    }
}
