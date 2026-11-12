package com.group2.incident.common.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import com.group2.incident.common.ratelimit.RateLimitExceededException;
import org.springframework.dao.OptimisticLockingFailureException;

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

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ApiError> missingRequestParameter(MissingServletRequestParameterException ex) {
		return respond(HttpStatus.BAD_REQUEST, "Validation failed",
				Map.of(ex.getParameterName(), "is required"));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ApiError> invalidRequestParameter(MethodArgumentTypeMismatchException ex) {
		return respond(HttpStatus.BAD_REQUEST, "Validation failed",
				Map.of(ex.getName(), "has an invalid value"));
	}

	// A body that isn't valid JSON, or a value of the wrong type, such as severity "SEV5".
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException ex) {
		return respond(HttpStatus.BAD_REQUEST, "Request body could not be read", Map.of());
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

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ApiError> optimisticLock(OptimisticLockingFailureException ex) {
		return respond(HttpStatus.CONFLICT, "This record was changed by another request; reload and try again", Map.of());
	}

	@ExceptionHandler(UnauthorizedException.class)
	ResponseEntity<ApiError> unauthorized(UnauthorizedException ex) {
		return respond(HttpStatus.UNAUTHORIZED, ex.getMessage(), Map.of());
	}

	// Same message for an unknown user and a wrong password, so login can't be used to find usernames.
	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ApiError> failedLogin(AuthenticationException ex) {
		return respond(HttpStatus.UNAUTHORIZED, "Invalid email or password", Map.of());
	}

	@ExceptionHandler(RateLimitExceededException.class)
	ResponseEntity<ApiError> rateLimitExceeded(RateLimitExceededException ex) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
				.body(new ApiError(HttpStatus.TOO_MANY_REQUESTS.value(), ex.getMessage(), Map.of()));
	}

	@ExceptionHandler(ServiceUnavailableException.class)
	ResponseEntity<ApiError> serviceUnavailable(ServiceUnavailableException ex) {
		return respond(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), Map.of());
	}

	private static ResponseEntity<ApiError> respond(HttpStatus status, String message, Map<String, String> errors) {
		return ResponseEntity.status(status).body(new ApiError(status.value(), message, errors));
	}

}
