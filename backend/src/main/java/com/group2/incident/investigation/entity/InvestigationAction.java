package com.group2.incident.investigation.entity;

import com.group2.incident.auth.entity.AppUser;
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

@Entity
@Table(name = "investigation_action")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvestigationAction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "investigation_id", nullable = false, updatable = false)
	private Investigation investigation;

	@Column(nullable = false, columnDefinition = "text")
	private String text;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private ActionDecision decision;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "decided_by")
	private AppUser decidedBy;

	@Column(name = "decided_at")
	private Instant decidedAt;

	public InvestigationAction(Investigation investigation, String text) {
		this.investigation = investigation;
		this.text = text;
	}

	public void decide(ActionDecision decision, AppUser actor, Instant decidedAt) {
		this.decision = decision;
		this.decidedBy = actor;
		this.decidedAt = decidedAt;
	}
}
