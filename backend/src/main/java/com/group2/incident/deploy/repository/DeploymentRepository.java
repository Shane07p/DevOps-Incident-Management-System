package com.group2.incident.deploy.repository;

import com.group2.incident.deploy.entity.Deployment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeploymentRepository extends JpaRepository<Deployment, Long> {

	@EntityGraph(attributePaths = { "service" })
	Optional<Deployment> findByServiceIdAndVersionAndDeployedAt(Long serviceId, String version, Instant deployedAt);

	@EntityGraph(attributePaths = { "service" })
	List<Deployment> findAllByServiceIdOrderByDeployedAtDescIdDesc(Long serviceId);

	@EntityGraph(attributePaths = { "service" })
	@Query("select d from Deployment d where d.service.id = :serviceId and d.deployedAt >= :from and d.deployedAt <= :to order by d.deployedAt desc, d.id desc")
	List<Deployment> findInWindow(
			@Param("serviceId") Long serviceId,
			@Param("from") Instant from,
			@Param("to") Instant to
	);
}
