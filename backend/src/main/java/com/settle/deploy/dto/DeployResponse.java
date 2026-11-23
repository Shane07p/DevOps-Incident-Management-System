package com.settle.deploy.dto;

import com.settle.deploy.entity.Deployment;
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
