package com.group2.incident.auth.dto;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.org.entity.Membership;

/** A member of an org: the person, with their role in that org. */
public record UserResponse(Long id, String email, String displayName, Role role) {

	public static UserResponse from(Membership membership) {
		AppUser user = membership.getUser();
		return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), membership.getRole());
	}

}
