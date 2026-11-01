package com.group2.incident.catalog.repository;

import com.group2.incident.catalog.entity.ServiceDependency;
import com.group2.incident.catalog.entity.ServiceDependencyId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceDependencyRepository extends JpaRepository<ServiceDependency, ServiceDependencyId> {

    @Query("SELECT sd.dependsOnId FROM ServiceDependency sd WHERE sd.serviceId = :serviceId ORDER BY sd.dependsOnId ASC")
    List<Long> findDependsOnIdsByServiceId(@Param("serviceId") Long serviceId);

    @Query("SELECT sd.serviceId FROM ServiceDependency sd WHERE sd.dependsOnId = :dependsOnId ORDER BY sd.serviceId ASC")
    List<Long> findDependentServiceIds(@Param("dependsOnId") Long dependsOnId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ServiceDependency sd WHERE sd.serviceId = :serviceId")
    void deleteByServiceId(@Param("serviceId") Long serviceId);
}
