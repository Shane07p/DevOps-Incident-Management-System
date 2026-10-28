package com.group2.incident.incident.entity;

import com.group2.incident.auth.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "escalation_step")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EscalationStep {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "policy_id", nullable = false)
	private EscalationPolicy policy;

	@Column(name = "step_number", nullable = false)
	private int stepNumber;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "responder_id", nullable = false)
	private AppUser responder;

	@Column(name = "wait_minutes", nullable = false)
	private int waitMinutes;

	public EscalationStep(int stepNumber, AppUser responder, int waitMinutes) {
		this.stepNumber = stepNumber;
		this.responder = responder;
		this.waitMinutes = waitMinutes;
	}

	void assignPolicy(EscalationPolicy policy) {
		this.policy = policy;
	}
}
