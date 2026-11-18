package com.group2.incident.auth.service;

import com.group2.incident.auth.entity.OAuthProvider;

/**
 * Who Google or GitHub says signed in (D50).
 *
 * @param subject the provider's own, permanent id for the account
 * @param email the account's email, or null if the provider gave none
 * @param emailVerified whether the provider says it verified that email; an unverified one is never used
 * @param displayName the name to start with, which the person can change when they sign up
 */
public record ProviderAccount(OAuthProvider provider, String subject, String email, boolean emailVerified, String displayName) {
}
