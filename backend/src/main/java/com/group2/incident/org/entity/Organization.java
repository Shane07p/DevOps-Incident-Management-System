package com.group2.incident.org.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An org: a company, a team, or one person (D48). Global: not filtered by org. */
@Entity
@Table(name = "organization")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String slug;

	@Column(nullable = false)
	private long nextIncidentNumber = 1;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	public Organization(String name, String slug, Instant createdAt) {
		this.name = name;
		this.slug = slug;
		this.createdAt = createdAt;
	}

	/** Returns this org's next incident number. Call on a row loaded with a write lock. */
	public long takeIncidentNumber() {
		return nextIncidentNumber++;
	}

}
