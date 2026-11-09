package com.group2.incident.deploy.entity;

import com.group2.incident.incident.entity.Incident;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Persists the relationship between an incident and a deployment (FR-20.1, US-19, #152).
 * Each link belongs to an incident and a deployment within the caller's organization.
 */
@Entity
@Table(name = "incident_deployment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncidentDeployment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incident_id", nullable = false, updatable = false)
	private Incident incident;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "deployment_id", nullable = false, updatable = false)
	private Deployment deployment;

	@Column(name = "linked_at", nullable = false, updatable = false)
	private Instant linkedAt;

	public IncidentDeployment(Incident incident, Deployment deployment, Instant linkedAt) {
		this.incident = incident;
		this.deployment = deployment;
		this.linkedAt = linkedAt;
	}
}
