package com.settle.auth.dto;

import com.settle.org.dto.OrgResponse;
import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt, UserResponse user, OrgResponse org) {
}
