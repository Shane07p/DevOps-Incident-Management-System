package com.settle.alert.repository;

import com.settle.alert.entity.MonitoringTool;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitoringToolRepository extends JpaRepository<MonitoringTool, Long> {

	Optional<MonitoringTool> findByTokenHash(String tokenHash);

	@Query("select t from MonitoringTool t where lower(t.name) = lower(:name)")
	Optional<MonitoringTool> findByNameIgnoreCase(@Param("name") String name);
}
