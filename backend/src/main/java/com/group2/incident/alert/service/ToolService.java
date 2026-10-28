package com.group2.incident.alert.service;

import com.group2.incident.alert.dto.CreateToolRequest;
import com.group2.incident.alert.dto.CreateToolResponse;
import com.group2.incident.alert.dto.ToolResponse;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.repository.MonitoringToolRepository;
import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

	private static final String TOKEN_PREFIX = "tt_";
	private static final int TOKEN_RANDOM_BYTES = 32;

	private final MonitoringToolRepository tools;
	private final AppUserRepository users;
	private final AuditService auditService;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	@Transactional(readOnly = true)
	public List<ToolResponse> listTools() {
		return tools.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
				.map(ToolResponse::from)
				.toList();
	}

	@Transactional
	public CreateToolResponse createTool(CreateToolRequest request, long adminUserId) {
		if (tools.findByNameIgnoreCase(request.name().trim()).isPresent()) {
			throw new ConflictException("Tool with name '" + request.name().trim() + "' already exists");
		}

		AppUser admin = users.findById(adminUserId)
				.orElseThrow(() -> new NotFoundException("Admin user " + adminUserId + " not found"));

		String rawToken = generateToken();
		String tokenHash = hashToken(rawToken);

		MonitoringTool newTool = new MonitoringTool(
				request.name().trim(),
				tokenHash,
				clock.instant()
		);
		MonitoringTool savedTool = tools.save(newTool);

		auditService.record(
				admin,
				AuditAction.TOOL_REGISTERED,
				AuditItemType.TOOL,
				savedTool.getId(),
				"name: " + savedTool.getName()
		);

		log.info("Admin {} registered tool {} (id: {})", admin.getUsername(), savedTool.getName(), savedTool.getId());
		return new CreateToolResponse(savedTool.getId(), savedTool.getName(), rawToken, savedTool.getCreatedAt());
	}

	@Transactional
	public void revokeTool(long id, long adminUserId) {
		MonitoringTool tool = tools.findById(id)
				.orElseThrow(() -> new NotFoundException("Tool " + id + " not found"));

		AppUser admin = users.findById(adminUserId)
				.orElseThrow(() -> new NotFoundException("Admin user " + adminUserId + " not found"));

		if (!tool.isRevoked()) {
			tool.revoke(clock.instant());
			auditService.record(
					admin,
					AuditAction.TOOL_REVOKED,
					AuditItemType.TOOL,
					tool.getId(),
					"name: " + tool.getName()
			);
			log.info("Admin {} revoked tool {} (id: {})", admin.getUsername(), tool.getName(), tool.getId());
		}
	}

	@Transactional(readOnly = true)
	public MonitoringTool validateToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			throw new UnauthorizedException("Missing or malformed Authorization header");
		}

		String tokenHash = hashToken(rawToken.trim());
		MonitoringTool tool = tools.findByTokenHash(tokenHash)
				.orElseThrow(() -> new UnauthorizedException("Invalid tool token"));

		if (tool.isRevoked()) {
			throw new UnauthorizedException("Tool token has been revoked");
		}

		return tool;
	}

	private String generateToken() {
		byte[] randomBytes = new byte[TOKEN_RANDOM_BYTES];
		secureRandom.nextBytes(randomBytes);
		return TOKEN_PREFIX + HexFormat.of().formatHex(randomBytes);
	}

	private static String hashToken(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 algorithm not available", e);
		}
	}
}
