package com.group2.incident.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * At most {@code value} bytes in UTF-8. BCrypt reads only the first 72 bytes of a password and
 * refuses longer ones, and a letter such as é takes two bytes, so a character limit is not enough.
 */
@Documented
@Constraint(validatedBy = MaxBytesValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxBytes {

	int value();

	String message() default "Too long";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
