package com.group2.incident.catalog.repository;

import com.group2.incident.catalog.entity.ServiceDependency;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceDependencyRepository extends JpaRepository<ServiceDependency, Long> {

	@Query("SELECT d.dependsOnId FROM ServiceDependency d WHERE d.serviceId = :serviceId ORDER BY d.dependsOnId ASC")
	List<Long> findDependsOnIdsByServiceId(@Param("serviceId") Long serviceId);

	List<ServiceDependency> findAllByOrderByServiceIdAscDependsOnIdAsc();

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("DELETE FROM ServiceDependency d WHERE d.serviceId = :serviceId")
	void deleteByServiceId(@Param("serviceId") Long serviceId);
}
