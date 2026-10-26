package com.group2.incident.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String username;

	@Column(nullable = false)
	private String displayName;

	@Column(nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(nullable = false)
	private boolean enabled = true;

	// Carried in every token as "tv"; a token with an older value is refused (D21).
	@Column(nullable = false)
	private int tokenVersion;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	public AppUser(String username, String displayName, String passwordHash, Role role, Instant createdAt) {
		this.username = username;
		this.displayName = displayName;
		this.passwordHash = passwordHash;
		this.role = role;
		this.createdAt = createdAt;
	}

	/** Every token issued so far stops working. Used by logout and, later, role changes. */
	public void revokeTokens() {
		tokenVersion++;
	}

	/** Updates the user's role and immediately invalidates all active tokens. */
	public void changeRole(Role newRole) {
		this.role = newRole;
		revokeTokens();
	}

}
