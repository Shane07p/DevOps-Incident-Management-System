package com.group2.incident.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.auth.repository.PersonalAccessTokenRepository;
import com.group2.incident.common.error.UnauthorizedException;
import com.group2.incident.common.ratelimit.RateLimitService;
import com.group2.incident.org.repository.MembershipRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionTemplate;

/** What the token check does before and after the database (#151). The full check runs in PersonalAccessTokenTest. */
class PersonalAccessTokenValidationTest {

	private final PersonalAccessTokenRepository tokens = mock(PersonalAccessTokenRepository.class);

	private final TransactionTemplate tx = mock(TransactionTemplate.class);

	private final PersonalAccessTokenService service = new PersonalAccessTokenService(tokens, mock(AppUserRepository.class),
			mock(MembershipRepository.class), mock(AuditService.class), mock(RateLimitService.class), tx,
			Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC));

	@Test
	void aTokenWithoutThePatPrefixIsRefusedWithoutALookup() {
		assertThatThrownBy(() -> service.validateToken(null)).isInstanceOf(UnauthorizedException.class);
		assertThatThrownBy(() -> service.validateToken("eyJhbGciOiJIUzI1NiJ9.session.jwt")).isInstanceOf(UnauthorizedException.class);

		verifyNoInteractions(tokens);
	}

	// Recording when a token was last used must never fail the request it came with.
	@Test
	void aFailureToRecordUseDoesNotFailTheRequest() {
		doThrow(new QueryTimeoutException("lock timeout")).when(tx).executeWithoutResult(any());

		assertThatCode(() -> service.recordUsage(5L)).doesNotThrowAnyException();
	}
}
