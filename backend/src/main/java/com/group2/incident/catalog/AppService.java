package com.group2.incident.catalog;

import com.group2.incident.auth.AppUser;
import com.group2.incident.incident.Severity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** A service in the catalog. Named AppService so it doesn't clash with Spring's {@code @Service}. */
@Entity
@Table(name = "service")
public class AppService {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	private String description;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private AppUser owner;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Criticality criticality;

	@ElementCollection
	@CollectionTable(name = "service_sla_target", joinColumns = @JoinColumn(name = "service_id"))
	@MapKeyEnumerated(EnumType.STRING)
	@MapKeyColumn(name = "severity")
	private Map<Severity, SlaTarget> targets = new EnumMap<>(Severity.class);

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected AppService() {
	}

	AppService(String name, String description, AppUser owner, Criticality criticality,
			Map<Severity, SlaTarget> targets, Instant now) {
		this.name = name;
		this.description = description;
		this.owner = owner;
		this.criticality = criticality;
		this.targets.putAll(targets);
		this.createdAt = now;
		this.updatedAt = now;
	}

	void update(String name, String description, AppUser owner, Criticality criticality,
			Map<Severity, SlaTarget> targets, Instant now) {
		this.name = name;
		this.description = description;
		this.owner = owner;
		this.criticality = criticality;
		// Change entries in place so Hibernate updates rows rather than replacing the collection.
		this.targets.putAll(targets);
		this.updatedAt = now;
	}

	/** The target an incident of this severity copies when it is created. */
	public SlaTarget targetFor(Severity severity) {
		SlaTarget target = targets.get(severity);
		if (target == null) {
			throw new IllegalStateException("Service " + id + " has no target for " + severity);
		}
		return target;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public AppUser getOwner() {
		return owner;
	}

	public Criticality getCriticality() {
		return criticality;
	}

	public Map<Severity, SlaTarget> getTargets() {
		return Map.copyOf(targets);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
