package com.group2.incident.catalog.entity;

import java.io.Serializable;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ServiceDependencyId implements Serializable {

    private Long serviceId;
    private Long dependsOnId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceDependencyId that = (ServiceDependencyId) o;
        return Objects.equals(serviceId, that.serviceId) && Objects.equals(dependsOnId, that.dependsOnId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceId, dependsOnId);
    }
}
