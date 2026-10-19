package com.group2.incident.common.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns our exceptions into {@link ApiError} responses. There is deliberately no catch-all
 * handler: it would also catch Spring Security's AccessDeniedException and hide the 403.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			errors.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		return respond(HttpStatus.BAD_REQUEST, "Validation failed", errors);
	}

	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> notReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (ex.getCause() instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException ife && !ife.getPath().isEmpty()) {
			String field = ife.getPath().get(0).getFieldName();
			errors.put(field, "Invalid value");
		}
		return respond(HttpStatus.BAD_REQUEST, "Validation failed", errors);
	}

	@ExceptionHandler(FieldValidationException.class)
	ResponseEntity<ApiError> invalidField(FieldValidationException ex) {
		return respond(HttpStatus.BAD_REQUEST, "Validation failed", Map.of(ex.getField(), ex.getMessage()));
	}

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<ApiError> notFound(NotFoundException ex) {
		return respond(HttpStatus.NOT_FOUND, ex.getMessage(), Map.of());
	}

	@ExceptionHandler(ConflictException.class)
	ResponseEntity<ApiError> conflict(ConflictException ex) {
		return respond(HttpStatus.CONFLICT, ex.getMessage(), Map.of());
	}

	// Same message for an unknown user and a wrong password, so login can't be used to find usernames.
	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ApiError> failedLogin(AuthenticationException ex) {
		return respond(HttpStatus.UNAUTHORIZED, "Invalid username or password", Map.of());
	}

	private static ResponseEntity<ApiError> respond(HttpStatus status, String message, Map<String, String> errors) {
		return ResponseEntity.status(status).body(new ApiError(status.value(), message, errors));
	}

}
