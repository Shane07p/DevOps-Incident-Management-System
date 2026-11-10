package com.group2.incident.statuspage.entity;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.incident.entity.Incident;
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
import org.hibernate.annotations.TenantId;

/**
 * A customer-facing update on an incident, kept apart from internal notes and comments (FR-21.1, D45).
 * Written once and never changed. The author is recorded here and on the timeline, never shown publicly.
 */
@Entity
@Table(name = "public_update")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublicUpdate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Filled and filtered by Hibernate from OrgContext (D48); never set by hand.
	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incident_id", nullable = false, updatable = false)
	private Incident incident;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private PublicUpdateStatus status;

	@Column(nullable = false, columnDefinition = "text", updatable = false)
	private String message;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false, updatable = false)
	private AppUser author;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public PublicUpdate(Incident incident, PublicUpdateStatus status, String message, AppUser author, Instant createdAt) {
		this.incident = incident;
		this.status = status;
		this.message = message;
		this.author = author;
		this.createdAt = createdAt;
	}
}
