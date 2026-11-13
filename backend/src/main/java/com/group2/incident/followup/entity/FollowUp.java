package com.group2.incident.followup.entity;

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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * An action item from an incident (FR-11.3, US-14): what to do, its kind, who owns it, and by when. It lives
 * apart from the incident row, so adding or finishing one never changes the incident's version, and it may
 * stay open after the incident closes (D17).
 */
@Entity
@Table(name = "follow_up")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FollowUp {

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

	@Column(nullable = false, length = 200)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private FollowUpKind kind;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private AppUser owner;

	@Column(name = "due_date", nullable = false)
	private LocalDate dueDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private FollowUpStatus status;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public FollowUp(Incident incident, String title, FollowUpKind kind, AppUser owner, LocalDate dueDate, Instant createdAt) {
		this.incident = incident;
		this.title = title;
		this.kind = kind;
		this.owner = owner;
		this.dueDate = dueDate;
		this.status = FollowUpStatus.OPEN;
		this.createdAt = createdAt;
	}

	public void rename(String title) {
		this.title = title;
	}

	public void changeKind(FollowUpKind kind) {
		this.kind = kind;
	}

	public void handTo(AppUser owner) {
		this.owner = owner;
	}

	public void moveDueDate(LocalDate dueDate) {
		this.dueDate = dueDate;
	}

	/** DONE records when it was done; moving back out of DONE clears that again. */
	public void moveTo(FollowUpStatus status, Instant now) {
		if (status == this.status) {
			return;
		}
		this.status = status;
		this.completedAt = status == FollowUpStatus.DONE ? now : null;
	}

	/** Past its due date (UTC) and not done. On the due date itself it is not overdue yet. */
	public boolean isOverdue(LocalDate today) {
		return status != FollowUpStatus.DONE && dueDate.isBefore(today);
	}
}
