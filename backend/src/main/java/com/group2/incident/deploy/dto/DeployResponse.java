package com.group2.incident.deploy.dto;

import com.group2.incident.deploy.entity.Deployment;
import java.time.Instant;

public record DeployResponse(
		Long id,
		String version,
		String commit,
		String author,
		Instant deployedAt
) {
	public static DeployResponse from(Deployment deployment) {
		return new DeployResponse(
				deployment.getId(),
				deployment.getVersion(),
				deployment.getCommit(),
				deployment.getAuthor(),
				deployment.getDeployedAt()
		);
	}
}
