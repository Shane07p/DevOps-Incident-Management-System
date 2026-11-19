package com.group2.incident.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A Google or GitHub account linked to a person (D50). Global like {@link AppUser}: it belongs to the person,
 * not to an org. The provider's own id finds the person on later sign-ins, even if their email changes there.
 */
@Entity
@Table(name = "user_identity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserIdentity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private AppUser user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 20)
	private OAuthProvider provider;

	// Google's "sub" or GitHub's numeric user id.
	@Column(nullable = false, updatable = false)
	private String subject;

	// The verified email the provider gave when the account was linked.
	@Column(nullable = false, updatable = false)
	private String email;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	public UserIdentity(AppUser user, OAuthProvider provider, String subject, String email, Instant createdAt) {
		this.user = user;
		this.provider = provider;
		this.subject = subject;
		this.email = email;
		this.createdAt = createdAt;
	}
}
