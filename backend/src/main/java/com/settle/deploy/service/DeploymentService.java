package com.settle.deploy.service;

import com.settle.alert.entity.MonitoringTool;
import com.settle.catalog.entity.ServiceEntity;
import com.settle.catalog.repository.ServiceRepository;
import com.settle.common.error.FieldValidationException;
import com.settle.common.error.NotFoundException;
import com.settle.common.error.UniqueKeys;
import com.settle.deploy.dto.DeployResponse;
import com.settle.deploy.dto.DeployWebhookRequest;
import com.settle.deploy.dto.DeployWebhookResponse;
import com.settle.deploy.entity.Deployment;
import com.settle.deploy.repository.DeploymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentService {

	private final DeploymentRepository deploymentRepository;
	private final ServiceRepository serviceRepository;
	private final Clock clock;

	@Transactional
	public DeployWebhookResponse processDeploy(MonitoringTool tool, DeployWebhookRequest request) {
		String serviceName = request.service();
		ServiceEntity service = serviceRepository.findByNameIgnoreCaseForUpdate(serviceName)
				.orElseThrow(() -> new FieldValidationException("service", "Service not found"));

		// 1. Check if a repeat already exists
		Optional<Deployment> existing = deploymentRepository.findByServiceIdAndVersionAndDeployedAt(
				service.getId(), request.version(), request.deployedAt());
		if (existing.isPresent()) {
			Deployment d = existing.get();
			log.info("Duplicate deploy ignored for service {} version {} deployedAt {}", serviceName, request.version(), request.deployedAt());
			return DeployWebhookResponse.duplicate(d.getId(), service.getName(), d.getVersion(), d.getCommit(), d.getAuthor(), d.getDeployedAt());
		}

		Instant now = clock.instant();
		Deployment deployment = new Deployment(
				service,
				request.version(),
				request.commit(),
				request.author(),
				request.deployedAt(),
				now
		);

		Deployment saved;
		try {
			saved = deploymentRepository.saveAndFlush(deployment);
		}
		catch (DataIntegrityViolationException e) {
			if (UniqueKeys.violated(e, "uq_deployment_service_version_time")) {
				log.info("Concurrent duplicate deploy caught for service {} version {} deployedAt {}", serviceName, request.version(), request.deployedAt());
				Deployment concurrent = deploymentRepository.findByServiceIdAndVersionAndDeployedAt(
						service.getId(), request.version(), request.deployedAt())
						.orElseThrow(() -> e);
				return DeployWebhookResponse.duplicate(concurrent.getId(), service.getName(), concurrent.getVersion(), concurrent.getCommit(), concurrent.getAuthor(), concurrent.getDeployedAt());
			}
			throw e;
		}

		log.info("Recorded deployment {} for service {} version {}", saved.getId(), service.getName(), saved.getVersion());
		return DeployWebhookResponse.recorded(saved.getId(), service.getName(), saved.getVersion(), saved.getCommit(), saved.getAuthor(), saved.getDeployedAt());
	}

	@Transactional(readOnly = true)
	public List<DeployResponse> listServiceDeploys(Long serviceId) {
		serviceRepository.findById(serviceId)
				.orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));
		return deploymentRepository.findAllByServiceIdOrderByDeployedAtDescIdDesc(serviceId)
				.stream()
				.map(DeployResponse::from)
				.toList();
	}
}
