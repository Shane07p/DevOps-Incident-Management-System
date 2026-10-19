package com.group2.incident.catalog.repository;

import com.group2.incident.catalog.entity.AppService;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppServiceRepository extends JpaRepository<AppService, Long> {

	@EntityGraph(attributePaths = { "owner", "targets" })
	Optional<AppService> findWithDetailsById(Long id);

	@EntityGraph(attributePaths = { "owner", "targets" })
	List<AppService> findAllByOrderByNameAsc();

	boolean existsByNameIgnoreCase(String name);
}
