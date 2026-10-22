package com.group2.incident.catalog.entity;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.incident.entity.Severity;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "service")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fallback_contact_id")
    private AppUser fallbackContact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Criticality criticality;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "service_sla_target",
            joinColumns = @JoinColumn(name = "service_id")
    )
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "severity", length = 10)
    private Map<Severity, SlaTarget> targets = new EnumMap<>(Severity.class);

    @OneToMany(mappedBy = "service", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startTime ASC")
    private List<OnCallShift> shifts = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ServiceEntity(String name, String description, AppUser owner, Criticality criticality, Instant createdAt) {
        this(null, name, description, owner, null, criticality, null, createdAt);
    }

    public ServiceEntity(Long id, String name, String description, AppUser owner, Criticality criticality,
                         Map<Severity, SlaTarget> targets, Instant createdAt) {
        this(id, name, description, owner, null, criticality, targets, createdAt);
    }

    public ServiceEntity(Long id, String name, String description, AppUser owner, AppUser fallbackContact,
                         Criticality criticality, Map<Severity, SlaTarget> targets, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.fallbackContact = fallbackContact;
        this.criticality = criticality;
        if (targets != null) {
            this.targets.putAll(targets);
        }
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void update(String name, String description, AppUser owner, Criticality criticality, Instant updatedAt) {
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.criticality = criticality;
        this.updatedAt = updatedAt;
    }

    public void setFallbackContact(AppUser fallbackContact) {
        this.fallbackContact = fallbackContact;
    }

    public void putTarget(Severity severity, int responseMinutes, int resolutionMinutes) {
        this.targets.put(severity, new SlaTarget(responseMinutes, resolutionMinutes));
    }

    public SlaTarget targetFor(Severity severity) {
        return targets.get(severity);
    }
}
