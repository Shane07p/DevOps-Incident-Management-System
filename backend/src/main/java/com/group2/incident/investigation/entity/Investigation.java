package com.group2.incident.investigation.entity;

import com.group2.incident.incident.entity.Incident;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "investigation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Investigation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incident_id", nullable = false, updatable = false)
	private Incident incident;

	@Column(nullable = false, columnDefinition = "text")
	private String summary;

	@Column(nullable = false, length = 40)
	private String status;

	@Column(name = "steps_taken", nullable = false)
	private int stepsTaken;

	@Column(name = "tokens_used", nullable = false)
	private int tokensUsed;

	@Column(name = "duration_ms", nullable = false)
	private double durationMs;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@OneToMany(mappedBy = "investigation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@OrderBy("rank ASC, id ASC")
	private List<InvestigationHypothesis> hypotheses = new ArrayList<>();

	@OneToMany(mappedBy = "investigation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@OrderBy("id ASC")
	private List<InvestigationAction> suggestedActions = new ArrayList<>();

	public Investigation(Incident incident, String summary, String status,
			int stepsTaken, int tokensUsed, double durationMs, Instant createdAt) {
		this.incident = incident;
		this.summary = summary;
		this.status = status;
		this.stepsTaken = stepsTaken;
		this.tokensUsed = tokensUsed;
		this.durationMs = durationMs;
		this.createdAt = createdAt;
	}

	public void addHypothesis(InvestigationHypothesis hypothesis) {
		hypotheses.add(hypothesis);
	}

	public void addAction(InvestigationAction action) {
		suggestedActions.add(action);
	}
}
