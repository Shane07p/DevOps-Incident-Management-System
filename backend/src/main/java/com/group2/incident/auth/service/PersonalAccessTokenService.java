package com.group2.incident.auth.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.dto.CreatePersonalAccessTokenRequest;
import com.group2.incident.auth.dto.CreatePersonalAccessTokenResponse;
import com.group2.incident.auth.dto.PersonalAccessTokenResponse;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.PersonalAccessToken;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.auth.repository.PersonalAccessTokenRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.error.UnauthorizedException;
import com.group2.incident.common.error.UniqueKeys;
import com.group2.incident.common.ratelimit.RateLimitService;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.MembershipRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalAccessTokenService {

	private static final String TOKEN_PREFIX = "pat_";
	private static final int TOKEN_RANDOM_BYTES = 32;
	private static final Duration TOKEN_EXPIRY = Duration.ofDays(90);

	private final PersonalAccessTokenRepository tokenRepository;
	private final AppUserRepository userRepository;
	private final MembershipRepository membershipRepository;
	private final AuditService auditService;
	private final RateLimitService rateLimitService;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	public record ValidatedPat(
			PersonalAccessToken token,
			AppUser user,
			Membership membership,
			Long orgId,
			Role role
	) {}

	@Transactional(readOnly = true)
	public List<PersonalAccessTokenResponse> listTokens(Long userId) {
		return tokenRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
				.map(PersonalAccessTokenResponse::from)
				.toList();
	}

	@Transactional
	public CreatePersonalAccessTokenResponse createToken(CreatePersonalAccessTokenRequest request, Long userId) {
		String name = request.name().trim();
		Long orgId = OrgContext.current();
		if (tokenRepository.findActiveByOrgIdAndUserIdAndNameIgnoreCase(orgId, userId, name).isPresent()) {
			throw new ConflictException("Token with name '" + name + "' already exists");
		}

		AppUser user = userRepository.findById(userId)
				.orElseThrow(() -> new NotFoundException("User " + userId + " not found"));

		String rawToken = generateToken();
		String tokenHash = hashToken(rawToken);
		Instant now = clock.instant();
		Instant expiresAt = now.plus(TOKEN_EXPIRY);

		PersonalAccessToken token = new PersonalAccessToken(user, name, tokenHash, now, expiresAt);
		PersonalAccessToken saved;
		try {
			saved = tokenRepository.saveAndFlush(token);
		}
		catch (DataIntegrityViolationException e) {
			if (UniqueKeys.violated(e, "uq_personal_access_token_user_name")) {
				throw new ConflictException("Token with name '" + name + "' already exists");
			}
			throw e;
		}

		auditService.record(
				user,
				AuditAction.TOKEN_CREATED,
				AuditItemType.TOKEN,
				saved.getId(),
				"name: " + saved.getName()
		);

		log.info("User {} created personal access token {} (id: {})", user.getId(), saved.getName(), saved.getId());
		return new CreatePersonalAccessTokenResponse(
				saved.getId(),
				saved.getName(),
				rawToken,
				saved.getCreatedAt(),
				saved.getExpiresAt()
		);
	}

	@Transactional
	public void revokeToken(Long id, Long userId) {
		PersonalAccessToken token = tokenRepository.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new NotFoundException("Personal access token " + id + " not found"));

		AppUser user = userRepository.findById(userId)
				.orElseThrow(() -> new NotFoundException("User " + userId + " not found"));

		if (!token.isRevoked()) {
			token.revoke(clock.instant());
			auditService.record(
					user,
					AuditAction.TOKEN_REVOKED,
					AuditItemType.TOKEN,
					token.getId(),
					"name: " + token.getName()
			);
			log.info("User {} revoked personal access token {} (id: {})", user.getId(), token.getName(), token.getId());
		}
	}

	public ValidatedPat validateToken(String rawToken) {
		if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
			throw new UnauthorizedException("Invalid personal access token");
		}

		String tokenHash = hashToken(rawToken.trim());
		PersonalAccessToken token = OrgContext.runAsRoot(() -> tokenRepository.findByTokenHash(tokenHash))
				.orElseThrow(() -> new UnauthorizedException("Invalid personal access token"));

		if (token.isRevoked()) {
			throw new UnauthorizedException("Personal access token has been revoked");
		}

		if (token.isExpired(clock.instant())) {
			throw new UnauthorizedException("Personal access token has expired");
		}

		Membership membership = OrgContext.runAsRoot(() ->
				membershipRepository.findByUserIdAndOrgId(token.getUser().getId(), token.getOrgId()))
				.orElseThrow(() -> new UnauthorizedException("User membership not found"));

		if (!membership.isEnabled()) {
			throw new UnauthorizedException("User account is disabled");
		}

		rateLimitService.checkPat(token.getId());
		recordUsage(token.getId());

		return new ValidatedPat(token, token.getUser(), membership, token.getOrgId(), membership.getRole());
	}

	public void recordUsage(Long tokenId) {
		try {
			tx.executeWithoutResult(status -> OrgContext.runAsRoot(() -> {
				tokenRepository.updateLastUsedAt(tokenId, clock.instant());
				return null;
			}));
		}
		catch (Exception e) {
			log.warn("Failed to update last_used_at for token {}: {}", tokenId, e.getMessage());
		}
	}

	private String generateToken() {
		byte[] randomBytes = new byte[TOKEN_RANDOM_BYTES];
		secureRandom.nextBytes(randomBytes);
		return TOKEN_PREFIX + HexFormat.of().formatHex(randomBytes);
	}

	public static String hashToken(String token) {
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

