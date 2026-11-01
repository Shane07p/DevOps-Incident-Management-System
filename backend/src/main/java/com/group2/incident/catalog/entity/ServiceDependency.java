package com.group2.incident.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "service_dependency")
@IdClass(ServiceDependencyId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceDependency {

    @Id
    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Id
    @Column(name = "depends_on_id", nullable = false)
    private Long dependsOnId;

    public ServiceDependency(Long serviceId, Long dependsOnId) {
        this.serviceId = serviceId;
        this.dependsOnId = dependsOnId;
    }
}
