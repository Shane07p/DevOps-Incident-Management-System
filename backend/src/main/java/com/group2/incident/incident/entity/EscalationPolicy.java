package com.group2.incident.incident.entity;

import com.group2.incident.catalog.entity.ServiceEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "escalation_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EscalationPolicy {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "service_id", nullable = false, unique = true)
	private ServiceEntity service;

	@OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("stepNumber ASC")
	private List<EscalationStep> steps = new ArrayList<>();

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public EscalationPolicy(ServiceEntity service, Instant createdAt) {
		this.service = service;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	public void replaceSteps(List<EscalationStep> newSteps, Instant updatedAt) {
		this.steps.clear();
		for (EscalationStep step : newSteps) {
			step.assignPolicy(this);
			this.steps.add(step);
		}
		this.updatedAt = updatedAt;
	}
}
