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
@Table(name = "escalation_task_step")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EscalationTaskStep {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "task_id", nullable = false)
	private EscalationTask task;

	@Column(name = "step_number", nullable = false)
	private int stepNumber;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "responder_id", nullable = false)
	private AppUser responder;

	@Column(name = "wait_minutes", nullable = false)
	private int waitMinutes;

	public EscalationTaskStep(EscalationTask task, int stepNumber, AppUser responder, int waitMinutes) {
		this.task = task;
		this.stepNumber = stepNumber;
		this.responder = responder;
		this.waitMinutes = waitMinutes;
	}
}
