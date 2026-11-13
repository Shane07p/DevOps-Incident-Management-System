package com.group2.incident.incident.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.common.error.ApiExceptionHandler;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.dto.IncidentRolesDto;
import com.group2.incident.incident.dto.IncidentServiceDto;
import com.group2.incident.incident.dto.IncidentUserDto;
import com.group2.incident.incident.dto.TimelineEntryResponse;
import com.group2.incident.incident.dto.TransitionRequest;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.service.IncidentService;
import com.group2.incident.incident.service.IncidentSimilarityService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Controller-layer test for {@code POST /api/incidents/{id}/transitions}.
 * Tests HTTP status codes, validation, error responses, and DTO contracts.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentControllerTransitionTest {

	private MockMvc mvc;

	@Mock
	private IncidentService incidentService;

	@Mock
	private IncidentSimilarityService incidentSimilarityService;

	@InjectMocks
	private IncidentController incidentController;

	private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(incidentController)
				.setCustomArgumentResolvers(jwtArgumentResolver(101L))
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
	}

	private HandlerMethodArgumentResolver jwtArgumentResolver(Long userId) {
		return new HandlerMethodArgumentResolver() {
			@Override
			public boolean supportsParameter(MethodParameter parameter) {
				return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
						&& parameter.getParameterType().equals(Jwt.class);
			}

			@Override
			public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
					NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
				return Jwt.withTokenValue("mock-token")
						.header("alg", "HS256")
						.subject(String.valueOf(userId))
						.build();
			}
		};
	}

	private IncidentResponse sampleResponse(IncidentStatus status, long version) {
		return new IncidentResponse(
				1L,
				"INC-42",
				"Database connection timeout",
				"Investigating root cause",
				new IncidentServiceDto(1L, "Checkout API"),
				Severity.SEV2,
				status,
				version,
				new IncidentUserDto(100L, "Reporter"),
				new IncidentUserDto(101L, "Dev Engineer"),
				new IncidentRolesDto(null, null),
				null,
				T0,
				60,
				480,
				T0.plusSeconds(3600),
				T0.plusSeconds(28800),
				T0,
				List.of(new TimelineEntryResponse(
						10L,
						TimelineEventType.STATUS_CHANGED,
						"Dev Engineer",
						"Status changed from OPEN to " + status,
						T0,
						null
				)),
				List.of()
		);
	}

	@Test
	@DisplayName("POST /api/incidents/{id}/transitions returns 200 OK with updated incident")
	void transitionSuccessReturns200() throws Exception {
		when(incidentService.transitionIncident(eq(1L), any(TransitionRequest.class), eq(101L)))
				.thenReturn(sampleResponse(IncidentStatus.INVESTIGATING, 2L));

		String body = "{\"status\":\"INVESTIGATING\",\"expectedVersion\":1}";

		mvc.perform(post("/api/incidents/1/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.status").value("INVESTIGATING"))
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.timeline[0].eventType").value("STATUS_CHANGED"));
	}

	@Test
	@DisplayName("POST /api/incidents/{id}/transitions with missing expectedVersion returns 400 Bad Request")
	void missingExpectedVersionReturns400() throws Exception {
		String body = "{\"status\":\"INVESTIGATING\"}";

		mvc.perform(post("/api/incidents/1/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.expectedVersion").isNotEmpty());
	}

	@Test
	@DisplayName("POST /api/incidents/{id}/transitions with missing status returns 400 Bad Request")
	void missingStatusReturns400() throws Exception {
		String body = "{\"expectedVersion\":1}";

		mvc.perform(post("/api/incidents/1/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.status").isNotEmpty());
	}

	@Test
	@DisplayName("POST /api/incidents/{id}/transitions with invalid status value returns 400 Bad Request")
	void invalidStatusValueReturns400() throws Exception {
		String body = "{\"status\":\"INVALID_STATUS\",\"expectedVersion\":1}";

		mvc.perform(post("/api/incidents/1/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("POST /api/incidents/{id}/transitions missing recovery note returns 400 Bad Request")
	void missingRecoveryNoteReturns400() throws Exception {
		when(incidentService.transitionIncident(eq(1L), any(TransitionRequest.class), eq(101L)))
				.thenThrow(new FieldValidationException("recoveryNote", "A recovery note is required to resolve an incident"));

		String body = "{\"status\":\"RESOLVED\",\"expectedVersion\":3}";

		mvc.perform(post("/api/incidents/1/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.recoveryNote").value("A recovery note is required to resolve an incident"));
	}

	@Test
	@DisplayName("POST /api/incidents/{id}/transitions for non-existent incident returns 404 Not Found")
	void nonExistentIncidentReturns404() throws Exception {
		when(incidentService.transitionIncident(eq(999L), any(TransitionRequest.class), eq(101L)))
				.thenThrow(new NotFoundException("Incident 999 not found"));

		String body = "{\"status\":\"INVESTIGATING\",\"expectedVersion\":1}";

		mvc.perform(post("/api/incidents/999/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Incident 999 not found"));
	}

	@Test
	@DisplayName("POST /api/incidents/{id}/transitions with stale version returns 409 Conflict")
	void staleVersionReturns409() throws Exception {
		when(incidentService.transitionIncident(eq(1L), any(TransitionRequest.class), eq(101L)))
				.thenThrow(new ConflictException("This record was changed by another request; reload and try again"));

		String body = "{\"status\":\"INVESTIGATING\",\"expectedVersion\":0}";

		mvc.perform(post("/api/incidents/1/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("This record was changed by another request; reload and try again"));
	}

	@Test
	@DisplayName("POST /api/incidents/{id}/transitions with illegal transition returns 409 Conflict")
	void illegalTransitionReturns409() throws Exception {
		when(incidentService.transitionIncident(eq(1L), any(TransitionRequest.class), eq(101L)))
				.thenThrow(new ConflictException("Cannot transition incident from OPEN to RESOLVED"));

		String body = "{\"status\":\"RESOLVED\",\"expectedVersion\":1,\"recoveryNote\":\"note\"}";

		mvc.perform(post("/api/incidents/1/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Cannot transition incident from OPEN to RESOLVED"));
	}
}
