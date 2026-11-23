package com.settle.auth.dto;

import com.settle.org.dto.OrgResponse;

/** The signed-in person and the org their token works for. */
public record MeResponse(UserResponse user, OrgResponse org) {
}
