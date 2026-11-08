package com.group2.incident.catalog.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** Step-by-step fix for one service (FR-25). Engineers and Admins write it; everyone reads it. */
@Entity
@Table(name = "runbook")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Runbook {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Filled and filtered by Hibernate from OrgContext (D48); never set by hand.
	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "service_id", nullable = false, updatable = false)
	private ServiceEntity service;

	@Column(nullable = false, length = 200)
	private String title;

	// Kept in the order given: the position column is each step's index.
	@ElementCollection
	@CollectionTable(name = "runbook_step", joinColumns = @JoinColumn(name = "runbook_id"))
	@OrderColumn(name = "position")
	@Column(name = "text", nullable = false, length = 2000)
	private List<String> steps = new ArrayList<>();

	// Two edits saved at once: the second fails instead of silently replacing the first.
	@Version
	@Column(nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public Runbook(ServiceEntity service, String title, List<String> steps, Instant now) {
		this.service = service;
		this.createdAt = now;
		change(title, steps, now);
	}

	public void change(String title, List<String> steps, Instant now) {
		this.title = title;
		this.steps.clear();
		this.steps.addAll(steps);
		this.updatedAt = now;
	}

	public List<String> getSteps() {
		return List.copyOf(steps);
	}
}
