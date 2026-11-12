package com.group2.incident.postmortem.entity;

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
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** The latest draft and its independent review; editing always invalidates that review (D17). */
@Entity
@Table(name = "postmortem")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Postmortem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incident_id", nullable = false, unique = true, updatable = false)
	private Incident incident;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PostmortemStatus status;

	@Column(columnDefinition = "text")
	private String summary;

	@Column(columnDefinition = "text")
	private String impact;

	@Column(name = "root_cause", columnDefinition = "text")
	private String rootCause;

	@Column(columnDefinition = "text")
	private String resolution;

	@Column(name = "went_well", columnDefinition = "text")
	private String wentWell;

	@Column(name = "went_badly", columnDefinition = "text")
	private String wentBadly;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private AppUser author;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reviewed_by")
	private AppUser reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(nullable = false)
	private Long version;

	public Postmortem(Incident incident, String summary, String impact, String rootCause,
			String resolution, String wentWell, String wentBadly, AppUser author, Instant now) {
		this.incident = incident;
		draft(summary, impact, rootCause, resolution, wentWell, wentBadly, author, now);
	}

	public void draft(String summary, String impact, String rootCause, String resolution,
			String wentWell, String wentBadly, AppUser editor, Instant now) {
		this.summary = summary;
		this.impact = impact;
		this.rootCause = rootCause;
		this.resolution = resolution;
		this.wentWell = wentWell;
		this.wentBadly = wentBadly;
		this.author = editor;
		this.status = PostmortemStatus.DRAFT;
		this.reviewedBy = null;
		this.reviewedAt = null;
		this.updatedAt = now;
	}

	public void review(AppUser reviewer, Instant now) {
		this.status = PostmortemStatus.REVIEWED;
		this.reviewedBy = reviewer;
		this.reviewedAt = now;
		this.updatedAt = now;
	}
}
