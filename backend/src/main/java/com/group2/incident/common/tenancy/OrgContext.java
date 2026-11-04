package com.group2.incident.common.tenancy;

import java.util.function.Supplier;

/**
 * The org the current thread works for (D48). Hibernate reads it when a session opens, through
 * {@link OrgTenantResolver}, so set it before a transaction starts. {@link #NONE} matches no row and
 * cannot be written; {@link #ROOT} sees every org and is only for the workers and credential lookups.
 */
public final class OrgContext {

	public static final long ROOT = 0L;

	public static final long NONE = -1L;

	private static final ThreadLocal<Long> CURRENT = ThreadLocal.withInitial(() -> NONE);

	private OrgContext() {
	}

	public static long current() {
		return CURRENT.get();
	}

	public static void set(long orgId) {
		CURRENT.set(orgId);
	}

	public static void clear() {
		CURRENT.remove();
	}

	/** Runs {@code work} for {@code orgId}, then puts back whatever was set before. */
	public static <T> T runAs(long orgId, Supplier<T> work) {
		long previous = current();
		set(orgId);
		try {
			return work.get();
		}
		finally {
			set(previous);
		}
	}

	public static <T> T runAsRoot(Supplier<T> work) {
		return runAs(ROOT, work);
	}

}
