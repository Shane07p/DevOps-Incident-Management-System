package com.group2.incident.audit.entity;

import com.group2.incident.auth.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "audit_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private AppUser actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 50)
    private AuditItemType itemType;

    @Column(nullable = false)
    private long itemId;

    @Column(nullable = false, columnDefinition = "text")
    private String details;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    public AuditEntry(AppUser actor, AuditAction action, AuditItemType itemType, long itemId,
                      String details, Instant recordedAt) {
        this.actor = actor;
        this.action = action;
        this.itemType = itemType;
        this.itemId = itemId;
        this.details = details;
        this.recordedAt = recordedAt;
    }
}
