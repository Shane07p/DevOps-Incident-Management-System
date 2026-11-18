package com.group2.incident.auth.dto;

import java.util.List;

/** The sign-in providers this deployment has set up, by registration id ("google", "github"); empty when none (D50). */
public record OAuthProvidersResponse(List<String> providers) {
}
