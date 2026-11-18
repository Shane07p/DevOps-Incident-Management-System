package com.group2.incident.auth.dto;

/** A new person's details from Google or GitHub, shown while they name their org (D50). */
public record OAuthPendingSignupResponse(String email, String displayName, String provider) {
}
