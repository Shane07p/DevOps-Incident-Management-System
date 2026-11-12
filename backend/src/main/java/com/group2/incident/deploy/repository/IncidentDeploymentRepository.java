package com.group2.incident.deploy.repository;

import com.group2.incident.deploy.entity.Deployment;
import com.group2.incident.deploy.entity.IncidentDeployment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentDeploymentRepository extends JpaRepository<IncidentDeployment, Long> {

	boolean existsByIncidentIdAndDeploymentId(Long incidentId, Long deploymentId);

	@Query("""
			select d.deployment from IncidentDeployment d
			join fetch d.deployment.service
			where d.incident.id = :incidentId
			order by d.deployment.deployedAt desc, d.deployment.id desc
			""")
	List<Deployment> findDeploymentsByIncidentId(@Param("incidentId") Long incidentId);

	List<IncidentDeployment> findAllByIncidentId(Long incidentId);
}
