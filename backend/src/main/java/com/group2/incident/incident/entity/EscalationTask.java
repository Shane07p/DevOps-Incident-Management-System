package com.group2.incident.incident.entity;

import com.group2.incident.auth.entity.AppUser;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "escalation_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EscalationTask {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incident_id", nullable = false, unique = true)
	private Incident incident;

	@Column(name = "current_step", nullable = false)
	private int currentStep;

	@Column(name = "next_due_at")
	private Instant nextDueAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private EscalationTaskState state;

	@Column(name = "fallback_notified", nullable = false)
	private boolean fallbackNotified;

	@OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("stepNumber ASC")
	private List<EscalationTaskStep> steps = new ArrayList<>();

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public EscalationTask(Incident incident, Instant nextDueAt, Instant createdAt) {
		this.incident = incident;
		this.currentStep = 0;
		this.nextDueAt = nextDueAt;
		this.state = EscalationTaskState.PENDING;
		this.fallbackNotified = false;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	public void addCopiedStep(int stepNumber, AppUser responder, int waitMinutes) {
		EscalationTaskStep step = new EscalationTaskStep(this, stepNumber, responder, waitMinutes);
		this.steps.add(step);
	}

	public void advanceToStep(int stepNumber, Instant nextDueAt, Instant updatedAt) {
		this.currentStep = stepNumber;
		this.nextDueAt = nextDueAt;
		this.updatedAt = updatedAt;
	}

	public void markFallbackNotified(Instant updatedAt) {
		this.fallbackNotified = true;
		this.state = EscalationTaskState.EXHAUSTED;
		this.nextDueAt = null;
		this.updatedAt = updatedAt;
	}

	public void markExhausted(Instant updatedAt) {
		this.state = EscalationTaskState.EXHAUSTED;
		this.nextDueAt = null;
		this.updatedAt = updatedAt;
	}

	public void cancel(Instant updatedAt) {
		this.state = EscalationTaskState.CANCELLED;
		this.nextDueAt = null;
		this.updatedAt = updatedAt;
	}
}
