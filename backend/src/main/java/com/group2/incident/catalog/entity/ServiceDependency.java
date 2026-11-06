package com.group2.incident.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** One link on the service map: {@code serviceId} depends on {@code dependsOnId} (FR-14.2). */
@Entity
@Table(name = "service_dependency")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceDependency {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Filled and filtered by Hibernate from OrgContext (D48); never set by hand.
	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@Column(name = "service_id", nullable = false, updatable = false)
	private Long serviceId;

	@Column(name = "depends_on_id", nullable = false, updatable = false)
	private Long dependsOnId;

	public ServiceDependency(Long serviceId, Long dependsOnId) {
		this.serviceId = serviceId;
		this.dependsOnId = dependsOnId;
	}
}
