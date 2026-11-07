package com.group2.incident.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A person. Global, not filtered by org (D48): the role and the enabled flag live on their
 * {@link com.group2.incident.org.entity.Membership}. A user id from a request must be checked against
 * the current org with {@link com.group2.incident.org.service.OrgMembers}.
 */
@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// The sign-in name, stored lower-case; unique regardless of case.
	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String displayName;

	// Null for accounts that only sign in with Google (part 2 of D48).
	private String passwordHash;

	// Carried in every token as "tv"; a token with an older value is refused (D21).
	@Column(nullable = false)
	private int tokenVersion;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	public AppUser(String email, String displayName, String passwordHash, Instant createdAt) {
		this.email = email.trim().toLowerCase(Locale.ROOT);
		this.displayName = displayName;
		this.passwordHash = passwordHash;
		this.createdAt = createdAt;
	}

}
