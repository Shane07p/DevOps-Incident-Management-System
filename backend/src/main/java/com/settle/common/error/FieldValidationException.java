package com.settle.common.error;

import lombok.Getter;

/** A rule on one request field that Bean Validation can't check, such as "this service exists". */
@Getter
public class FieldValidationException extends RuntimeException {

	private final String field;

	public FieldValidationException(String field, String message) {
		super(message);
		this.field = field;
	}

}
