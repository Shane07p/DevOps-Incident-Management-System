package com.group2.incident.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppServiceRepository extends JpaRepository<AppService, Long> {

	@EntityGraph(attributePaths = { "owner", "targets" })
	List<AppService> findAllByOrderByNameAsc();

	@EntityGraph(attributePaths = { "owner", "targets" })
	Optional<AppService> findWithDetailsById(Long id);

	boolean existsByNameIgnoreCase(String name);

	boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

}
