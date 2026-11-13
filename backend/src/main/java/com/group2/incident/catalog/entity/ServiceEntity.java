package com.group2.incident.catalog.entity;

import com.group2.incident.auth.entity.AppUser;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "service")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Filled and filtered by Hibernate from OrgContext (D48); never set by hand.
    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private Long orgId;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    // Gets new incidents when nobody is on call (FR-15).
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

    // Shown on the org's public status page, under publicName (D45).
    @Column(name = "public_visible", nullable = false)
    private boolean publicVisible;

    @Column(name = "public_name", length = 100)
    private String publicName;

    /** The value of the org's service label for this service (D49); null means {@link #defaultTelemetryLabel}. */
    @Column(name = "telemetry_label", length = 100)
    private String telemetryLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ServiceEntity(String name, String description, AppUser owner, Criticality criticality, Instant createdAt) {
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.criticality = criticality;
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

    /** Shows the service on the public status page under {@code publicName}, or hides it (D45). */
    public void publishAs(boolean visible, String publicName, Instant updatedAt) {
        this.publicVisible = visible;
        this.publicName = publicName;
        this.updatedAt = updatedAt;
    }

    /** The label value to query this service's logs and metrics by: the saved one, or the default. */
    public String telemetryLabel() {
        return telemetryLabel != null ? telemetryLabel : defaultTelemetryLabel(name);
    }

    /** Sets the label value; null goes back to the default. */
    public void labelTelemetryAs(String telemetryLabel) {
        this.telemetryLabel = telemetryLabel;
    }

    /** The name in lower case, words joined by hyphens: what the demo shop and many setups use. */
    public static String defaultTelemetryLabel(String name) {
        return name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "-");
    }

    public void changeFallbackContact(AppUser fallbackContact) {
        this.fallbackContact = fallbackContact;
    }

    public void putTarget(Severity severity, int responseMinutes, int resolutionMinutes) {
        this.targets.put(severity, new SlaTarget(responseMinutes, resolutionMinutes));
    }
}
