package com.group2.incident.auth.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.dto.CreateServiceRequest;
import com.group2.incident.catalog.dto.SlaTargetDto;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.service.ServiceCatalogService;
import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
public class SecurityMatrixTest {

    private static final String PASSWORD = "DevPassw0rd!";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private ServiceCatalogService serviceCatalogService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private String adminToken;
    private String engineerToken;
    private String viewerToken;
    private Long testServiceId;

    @BeforeEach
    void setUp() throws Exception {
        AppUser admin = createUser("admin", Role.ADMIN);
        createUser("engineer", Role.ENGINEER);
        createUser("viewer", Role.VIEWER);

        adminToken = tokenFor("admin");
        engineerToken = tokenFor("engineer");
        viewerToken = tokenFor("viewer");

        SlaTargetDto target = new SlaTargetDto(15, 240);
        Map<Severity, SlaTargetDto> targets = Map.of(
                Severity.SEV1, target,
                Severity.SEV2, target,
                Severity.SEV3, target,
                Severity.SEV4, target
        );
        CreateServiceRequest request = new CreateServiceRequest("BaseService", "Initial", admin.getId(), Criticality.HIGH, targets);
        testServiceId = serviceCatalogService.create(request, admin.getId()).id();
    }

    @Test
    void servicesGetListPermissions() throws Exception {
        mvc.perform(get("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/services"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servicesGetOnePermissions() throws Exception {
        mvc.perform(get("/api/services/" + testServiceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/services/" + testServiceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/services/" + testServiceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/services/" + testServiceId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servicesPostPermissions() throws Exception {
        String body = sampleServiceBody("NewService");

        mvc.perform(post("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servicesPutPermissions() throws Exception {
        String body = sampleServiceBody("BaseService");

        mvc.perform(put("/api/services/" + testServiceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mvc.perform(put("/api/services/" + testServiceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/services/" + testServiceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/services/" + testServiceId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
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

    private String sampleServiceBody(String name) {
        AppUser admin = users.findByUsername("admin").orElseThrow();
        String one = "{\"responseMinutes\": 15, \"resolutionMinutes\": 240}";
        String targets = "{\"SEV1\": %s, \"SEV2\": %s, \"SEV3\": %s, \"SEV4\": %s}".formatted(one, one, one, one);
        return """
                {"name": "%s", "description": "Desc", "ownerId": %d, "criticality": "HIGH", "targets": %s}
                """.formatted(name, admin.getId(), targets);
    }
}
