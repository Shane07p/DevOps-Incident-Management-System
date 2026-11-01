package com.group2.incident.catalog.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServiceDependencyTest {

    @Test
    void serviceDependencyEntityAndIdCoverage() {
        ServiceDependencyId id1 = new ServiceDependencyId(1L, 2L);
        ServiceDependencyId id2 = new ServiceDependencyId(1L, 2L);
        ServiceDependencyId id3 = new ServiceDependencyId(1L, 3L);
        ServiceDependencyId empty = new ServiceDependencyId();

        assertThat(id1).isEqualTo(id2);
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
        assertThat(id1).isNotEqualTo(id3);
        assertThat(id1).isNotEqualTo(null);
        assertThat(id1).isNotEqualTo("other");
        assertThat(id1.getServiceId()).isEqualTo(1L);
        assertThat(id1.getDependsOnId()).isEqualTo(2L);
        assertThat(empty.getServiceId()).isNull();

        ServiceDependency dep = new ServiceDependency(1L, 2L);
        assertThat(dep.getServiceId()).isEqualTo(1L);
        assertThat(dep.getDependsOnId()).isEqualTo(2L);
    }
}
