package com.settle.auth.entity;

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
 * A finished Google or GitHub sign-in, waiting for the browser to redeem it (D50). The browser gets the code in
 * the URL fragment, never a token: fragments stay out of server logs and Referer headers. Only the code's SHA-256
 * is stored, and {@code usedAt} makes it work once.
 */
@Entity
@Table(name = "oauth_ticket")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthTicket {

	/** LOGIN names an existing person; SIGNUP holds a new person's details until they name their org. */
	public enum Kind { LOGIN, SIGNUP }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false, length = 64)
	private String codeHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 10)
	private Kind kind;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", updatable = false)
	private AppUser user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 20)
	private OAuthProvider provider;

	@Column(nullable = false, updatable = false)
	private String subject;

	@Column(nullable = false, updatable = false)
	private String email;

	@Column(nullable = false, updatable = false)
	private String displayName;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false, updatable = false)
	private Instant expiresAt;

	private Instant usedAt;

	public OAuthTicket(String codeHash, Kind kind, AppUser user, OAuthProvider provider, String subject, String email,
			String displayName, Instant createdAt, Instant expiresAt) {
		this.codeHash = codeHash;
		this.kind = kind;
		this.user = user;
		this.provider = provider;
		this.subject = subject;
		this.email = email;
		this.displayName = displayName;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}
}
