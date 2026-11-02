package com.group2.incident.support;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.entity.Organization;
import java.time.Instant;

/** Memberships for unit tests that mock {@code OrgMembers} or {@code MembershipRepository} (D48). */
public final class Members {

	private static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");

	private static final Organization ORG = new Organization("Test org", "test-org", CREATED);

	private Members() {
	}

	public static Membership of(AppUser user, Role role) {
		return new Membership(ORG, user, role, CREATED);
	}

}
