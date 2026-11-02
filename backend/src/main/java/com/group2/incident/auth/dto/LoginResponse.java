package com.group2.incident.auth.dto;

import com.group2.incident.org.dto.OrgResponse;
import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt, UserResponse user, OrgResponse org) {
}
