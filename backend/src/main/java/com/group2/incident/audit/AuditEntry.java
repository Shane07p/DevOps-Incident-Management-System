package com.group2.incident.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** One configuration change: who did what to which item, and when. Never updated or deleted. */
@Entity
@Immutable
@Table(name = "audit_entry")
public class AuditEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "actor_id", nullable = false)
	private Long actorId;

	@Column(nullable = false)
	private String action;

	@Column(name = "item_type", nullable = false)
	private String itemType;

	@Column(name = "item_id")
	private Long itemId;

	@Column(columnDefinition = "text")
	private String details;

	@Column(name = "recorded_at", nullable = false)
	private Instant recordedAt;

	protected AuditEntry() {
	}

	AuditEntry(Long actorId, String action, String itemType, Long itemId, String details, Instant recordedAt) {
		this.actorId = actorId;
		this.action = action;
		this.itemType = itemType;
		this.itemId = itemId;
		this.details = details;
		this.recordedAt = recordedAt;
	}

	public Long getId() {
		return id;
	}

	public Long getActorId() {
		return actorId;
	}

	public String getAction() {
		return action;
	}

	public String getItemType() {
		return itemType;
	}

	public Long getItemId() {
		return itemId;
	}

	public String getDetails() {
		return details;
	}

	public Instant getRecordedAt() {
		return recordedAt;
	}

}
