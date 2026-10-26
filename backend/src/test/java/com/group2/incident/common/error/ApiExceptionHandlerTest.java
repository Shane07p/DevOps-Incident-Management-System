package com.group2.incident.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
	}

	@Test
	void invalidRequestBodyReturns400NamingTheField() throws Exception {
		mvc.perform(post("/probe/validated").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errors.title").value("Title is required"));
	}

	@Test
	void fieldValidationExceptionReturns400NamingTheField() throws Exception {
		mvc.perform(get("/probe/field"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errors.serviceId").value("Service not found"));
	}

	@Test
	void notFoundExceptionReturns404() throws Exception {
		mvc.perform(get("/probe/missing"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Incident 7 not found"))
				.andExpect(jsonPath("$.errors").isEmpty());
	}

	@Test
	void conflictExceptionReturns409() throws Exception {
		mvc.perform(get("/probe/duplicate"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Username already taken"));
	}

	@Test
	void failedLoginReturns401WithoutSayingWhichPartWasWrong() throws Exception {
		mvc.perform(get("/probe/login"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid username or password"));
	}

	@Test
	void unauthorizedExceptionReturns401() throws Exception {
		mvc.perform(get("/probe/unauthorized"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Tool token has been revoked"))
				.andExpect(jsonPath("$.errors").isEmpty());
	}

	@RestController
	@RequestMapping("/probe")
	static class ProbeController {

		record TitleRequest(@NotBlank(message = "Title is required") String title) {
		}

		@PostMapping("/validated")
		void validated(@Valid @RequestBody TitleRequest request) {
		}

		@GetMapping("/field")
		void field() {
			throw new FieldValidationException("serviceId", "Service not found");
		}

		@GetMapping("/missing")
		void missing() {
			throw new NotFoundException("Incident 7 not found");
		}

		@GetMapping("/duplicate")
		void duplicate() {
			throw new ConflictException("Username already taken");
		}

		@GetMapping("/login")
		void login() {
			throw new BadCredentialsException("Bad credentials");
		}

		@GetMapping("/unauthorized")
		void unauthorized() {
			throw new UnauthorizedException("Tool token has been revoked");
		}

	}

}
