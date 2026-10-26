package com.group2.incident.alert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.alert.dto.CreateToolRequest;
import com.group2.incident.alert.dto.CreateToolResponse;
import com.group2.incident.alert.dto.ToolResponse;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.repository.MonitoringToolRepository;
import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ToolServiceTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-10-20T12:00:00Z");

	@Mock
	private MonitoringToolRepository tools;

	@Mock
	private AppUserRepository users;

	@Mock
	private AuditService auditService;

	private Clock clock;
	private ToolService toolService;
	private AppUser admin;

	@BeforeEach
	void setUp() {
		clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
		toolService = new ToolService(tools, users, auditService, clock);
		admin = new AppUser("admin", "Admin User", "{noop}pass", Role.ADMIN, FIXED_NOW);
		ReflectionTestUtils.setField(admin, "id", 1L);
	}

	@Test
	void createTool_generatesSecureToken_hashesWithSha256_recordsAudit_andReturnsPlaintextToken() throws Exception {
		when(tools.findByNameIgnoreCase("Prometheus")).thenReturn(Optional.empty());
		when(users.findById(1L)).thenReturn(Optional.of(admin));
		when(tools.save(any(MonitoringTool.class))).thenAnswer(inv -> {
			MonitoringTool t = inv.getArgument(0);
			ReflectionTestUtils.setField(t, "id", 42L);
			return t;
		});

		CreateToolResponse response = toolService.createTool(new CreateToolRequest("Prometheus"), 1L);

		assertThat(response.id()).isEqualTo(42L);
		assertThat(response.name()).isEqualTo("Prometheus");
		assertThat(response.token()).startsWith("tt_");
		assertThat(response.token().length()).isGreaterThan(60);
		assertThat(response.createdAt()).isEqualTo(FIXED_NOW);

		ArgumentCaptor<MonitoringTool> toolCaptor = ArgumentCaptor.forClass(MonitoringTool.class);
		verify(tools).save(toolCaptor.capture());
		MonitoringTool saved = toolCaptor.getValue();
		assertThat(saved.getName()).isEqualTo("Prometheus");

		// Stored hash must match SHA-256 of raw token
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] expectedHashBytes = digest.digest(response.token().getBytes(StandardCharsets.UTF_8));
		String expectedHash = HexFormat.of().formatHex(expectedHashBytes);
		assertThat(saved.getTokenHash()).isEqualTo(expectedHash);
		assertThat(saved.getTokenHash()).isNotEqualTo(response.token());

		verify(auditService).record(
				eq(admin),
				eq(AuditAction.TOOL_REGISTERED),
				eq(AuditItemType.TOOL),
				eq(42L),
				eq("name: Prometheus")
		);
	}

	@Test
	void createTool_duplicateName_throwsConflictException() {
		when(tools.findByNameIgnoreCase("Prometheus"))
				.thenReturn(Optional.of(new MonitoringTool("Prometheus", "hash", FIXED_NOW)));

		assertThatThrownBy(() -> toolService.createTool(new CreateToolRequest("Prometheus"), 1L))
				.isInstanceOf(ConflictException.class)
				.hasMessage("Tool with name 'Prometheus' already exists");
	}

	@Test
	void createTool_unknownAdminId_throwsNotFoundException() {
		when(tools.findByNameIgnoreCase("Prometheus")).thenReturn(Optional.empty());
		when(users.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> toolService.createTool(new CreateToolRequest("Prometheus"), 99L))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("Admin user 99 not found");
	}

	@Test
	void listTools_returnsToolsWithoutTokens() {
		MonitoringTool t1 = new MonitoringTool("T1", "hash1", FIXED_NOW);
		ReflectionTestUtils.setField(t1, "id", 10L);
		MonitoringTool t2 = new MonitoringTool("T2", "hash2", FIXED_NOW.minusSeconds(60));
		ReflectionTestUtils.setField(t2, "id", 11L);

		when(tools.findAll(any(Sort.class))).thenReturn(List.of(t1, t2));

		List<ToolResponse> list = toolService.listTools();
		assertThat(list).hasSize(2);
		assertThat(list.get(0).id()).isEqualTo(10L);
		assertThat(list.get(0).name()).isEqualTo("T1");
		assertThat(list.get(0).createdAt()).isEqualTo(FIXED_NOW);
		assertThat(list.get(0).revokedAt()).isNull();
	}

	@Test
	void revokeTool_setsRevokedAtAndRecordsAudit() {
		MonitoringTool tool = new MonitoringTool("T1", "hash1", FIXED_NOW);
		ReflectionTestUtils.setField(tool, "id", 10L);

		when(tools.findById(10L)).thenReturn(Optional.of(tool));
		when(users.findById(1L)).thenReturn(Optional.of(admin));

		toolService.revokeTool(10L, 1L);

		assertThat(tool.isRevoked()).isTrue();
		assertThat(tool.getRevokedAt()).isEqualTo(FIXED_NOW);

		verify(auditService).record(
				eq(admin),
				eq(AuditAction.TOOL_REVOKED),
				eq(AuditItemType.TOOL),
				eq(10L),
				eq("name: T1")
		);
	}

	@Test
	void revokeTool_unknownId_throwsNotFoundException() {
		when(tools.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> toolService.revokeTool(999L, 1L))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("Tool 999 not found");
	}

	@Test
	void validateToken_validToken_returnsTool() throws Exception {
		String rawToken = "tt_sample_token_12345";
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		String hash = HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));

		MonitoringTool tool = new MonitoringTool("Valid Tool", hash, FIXED_NOW);
		when(tools.findByTokenHash(hash)).thenReturn(Optional.of(tool));

		MonitoringTool result = toolService.validateToken(rawToken);
		assertThat(result).isSameAs(tool);
	}

	@Test
	void validateToken_missingOrEmptyToken_throwsUnauthorizedException() {
		assertThatThrownBy(() -> toolService.validateToken(null))
				.isInstanceOf(UnauthorizedException.class)
				.hasMessage("Missing or malformed Authorization header");

		assertThatThrownBy(() -> toolService.validateToken("   "))
				.isInstanceOf(UnauthorizedException.class)
				.hasMessage("Missing or malformed Authorization header");
	}

	@Test
	void validateToken_unknownHash_throwsUnauthorizedException() {
		when(tools.findByTokenHash(any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> toolService.validateToken("tt_unknown"))
				.isInstanceOf(UnauthorizedException.class)
				.hasMessage("Invalid tool token");
	}

	@Test
	void validateToken_revokedTool_throwsUnauthorizedException() throws Exception {
		String rawToken = "tt_revoked";
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		String hash = HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));

		MonitoringTool tool = new MonitoringTool("Revoked Tool", hash, FIXED_NOW);
		tool.revoke(FIXED_NOW);
		when(tools.findByTokenHash(hash)).thenReturn(Optional.of(tool));

		assertThatThrownBy(() -> toolService.validateToken(rawToken))
				.isInstanceOf(UnauthorizedException.class)
				.hasMessage("Tool token has been revoked");
	}

	@Test
	void tokenNeverAppearsInLogsDuringToolRegistration() {
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ToolService.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> listAppender = new ch.qos.logback.core.read.ListAppender<>();
		listAppender.start();
		logger.addAppender(listAppender);

		when(tools.findByNameIgnoreCase("Log Tool")).thenReturn(Optional.empty());
		when(users.findById(1L)).thenReturn(Optional.of(admin));
		when(tools.save(any(MonitoringTool.class))).thenAnswer(inv -> {
			MonitoringTool t = inv.getArgument(0);
			ReflectionTestUtils.setField(t, "id", 99L);
			return t;
		});

		try {
			CreateToolResponse response = toolService.createTool(new CreateToolRequest("Log Tool"), 1L);
			String rawToken = response.token();

			for (ch.qos.logback.classic.spi.ILoggingEvent event : listAppender.list) {
				assertThat(event.getFormattedMessage()).doesNotContain(rawToken);
				if (event.getArgumentArray() != null) {
					for (Object arg : event.getArgumentArray()) {
						if (arg != null) {
							assertThat(arg.toString()).doesNotContain(rawToken);
						}
					}
				}
			}
		}
		finally {
			logger.detachAppender(listAppender);
			listAppender.stop();
		}
	}
}
