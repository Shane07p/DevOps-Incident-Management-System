package com.group2.incident.alert.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.alert.dto.CreateToolRequest;
import com.group2.incident.alert.dto.CreateToolResponse;
import com.group2.incident.alert.dto.ToolResponse;
import com.group2.incident.alert.service.ToolService;
import com.group2.incident.common.error.ApiExceptionHandler;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ToolControllerStandaloneTest {

	@Mock
	private ToolService toolService;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		ToolController controller = new ToolController(toolService);
		mvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new ApiExceptionHandler())
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.build();

		Jwt jwt = Jwt.withTokenValue("mock-admin-token")
				.header("alg", "none")
				.claim("sub", "1")
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(3600))
				.build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void listTools_returns200WithList() throws Exception {
		when(toolService.listTools()).thenReturn(List.of(
				new ToolResponse(1L, "Prometheus", Instant.parse("2026-10-20T12:00:00Z"), null)
		));

		mvc.perform(get("/api/tools"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(1))
				.andExpect(jsonPath("$[0].name").value("Prometheus"))
				.andExpect(jsonPath("$[0].createdAt").value("2026-10-20T12:00:00Z"))
				.andExpect(jsonPath("$[0].revokedAt").doesNotExist());
	}

	@Test
	void createTool_validRequest_returns201WithPlaintextToken() throws Exception {
		when(toolService.createTool(any(CreateToolRequest.class), eq(1L)))
				.thenReturn(new CreateToolResponse(1L, "Datadog", "tt_secret123", Instant.parse("2026-10-20T12:00:00Z")));

		mvc.perform(post("/api/tools")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Datadog\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.name").value("Datadog"))
				.andExpect(jsonPath("$.token").value("tt_secret123"));
	}

	@Test
	void createTool_blankName_returns400() throws Exception {
		mvc.perform(post("/api/tools")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.name").isNotEmpty());
	}

	@Test
	void createTool_duplicateName_returns409() throws Exception {
		when(toolService.createTool(any(CreateToolRequest.class), eq(1L)))
				.thenThrow(new ConflictException("Tool with name 'Datadog' already exists"));

		mvc.perform(post("/api/tools")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Datadog\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value("Tool with name 'Datadog' already exists"));
	}

	@Test
	void revokeTool_existingId_returns204() throws Exception {
		mvc.perform(delete("/api/tools/5"))
				.andExpect(status().isNoContent());

		verify(toolService).revokeTool(5L, 1L);
	}

	@Test
	void revokeTool_unknownId_returns404() throws Exception {
		org.mockito.Mockito.doThrow(new NotFoundException("Tool 99 not found"))
				.when(toolService).revokeTool(99L, 1L);

		mvc.perform(delete("/api/tools/99"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Tool 99 not found"));
	}
}
