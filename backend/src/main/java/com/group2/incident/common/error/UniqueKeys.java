package com.group2.incident.common.error;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Which unique key a failed insert broke. A check before an insert cannot rule out a second request
 * doing the same at the same moment; the database's unique key does, and this tells the caller which.
 */
public final class UniqueKeys {

	private UniqueKeys() {
	}

	public static boolean violated(DataIntegrityViolationException e, String key) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation && key.equalsIgnoreCase(violation.getConstraintName())) {
				return true;
			}
			if (cause.getMessage() != null && cause.getMessage().contains("\"" + key + "\"")) {
				return true;
			}
		}
		return false;
	}

}
