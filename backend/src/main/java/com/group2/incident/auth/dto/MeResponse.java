package com.group2.incident.auth.dto;

import com.group2.incident.org.dto.OrgResponse;

/** The signed-in person and the org their token works for. */
public record MeResponse(UserResponse user, OrgResponse org) {
}
