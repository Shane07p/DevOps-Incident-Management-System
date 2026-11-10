package com.group2.incident.deploy.entity;

import com.group2.incident.catalog.entity.ServiceEntity;
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

@Entity
@Table(name = "deployment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deployment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "org_id", nullable = false, updatable = false)
	private Long orgId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "service_id", nullable = false, updatable = false)
	private ServiceEntity service;

	@Column(name = "version", nullable = false, length = 100, updatable = false)
	private String version;

	@Column(name = "commit_hash", nullable = false, length = 100, updatable = false)
	private String commit;

	@Column(name = "author", nullable = false, length = 100, updatable = false)
	private String author;

	@Column(name = "deployed_at", nullable = false, updatable = false)
	private Instant deployedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public Deployment(ServiceEntity service, String version, String commit, String author, Instant deployedAt) {
		this(service, version, commit, author, deployedAt, deployedAt);
	}

	public Deployment(ServiceEntity service, String version, String commit, String author, Instant deployedAt, Instant createdAt) {
		this.service = service;
		this.version = version;
		this.commit = commit;
		this.author = author;
		this.deployedAt = deployedAt;
		this.createdAt = createdAt;
	}
}
