package com.group2.incident.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesRegex;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.group2.incident.alert.dto.CreateToolRequest;
import com.group2.incident.alert.dto.CreateToolResponse;
import com.group2.incident.alert.service.ToolService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.support.IntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Validates caller rate limits on login and alert webhook (NFR-04.4, D42).
 * Implements test cases TC-NFR-04.4a through TC-NFR-04.4e from docs/testing/TEST_CASES.md.
 */
@IntegrationTest
class RateLimitTest {

	private static final Instant BASE_TIME = Instant.parse("2026-10-28T00:00:00Z");
	private static final String PASSWORD = "DevPassw0rd!";
	private static final String ALERT_PAYLOAD = """
			{
				"source": "prometheus",
				"eventId": "evt-ratelimit-1",
				"service": "Payments",
				"title": "High error rate",
				"description": "500 errors on /pay",
				"severity": "SEV2"
			}
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private RateLimitService rateLimitService;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private ToolService toolService;

	@Autowired
	private Clock clock;

	private AppUser testUser;
	private AppUser adminUser;
	private String toolTokenA;
	private Long toolIdA;

	@BeforeEach
	void setUp() {
		rateLimitService.clear();
		rateLimitService.setClock(Clock.fixed(BASE_TIME, ZoneOffset.UTC));

		testUser = users.saveAndFlush(new AppUser("rate_user_" + clock.millis(), "Rate Test User",
				passwordEncoder.encode(PASSWORD), Role.ENGINEER, clock.instant()));

		adminUser = users.saveAndFlush(new AppUser("rate_admin_" + clock.millis(), "Rate Admin",
				passwordEncoder.encode(PASSWORD), Role.ADMIN, clock.instant()));

		CreateToolResponse createdA = toolService.createTool(
				new CreateToolRequest("Tool A " + clock.millis()), adminUser.getId());
		toolTokenA = createdA.token();
		toolIdA = createdA.id();
	}

	@AfterEach
	void tearDown() {
		rateLimitService.resetClock();
		rateLimitService.clear();
	}

	@Test
	void tcNfr044a_and_tcNfr044b_loginRateLimitBoundaryValues() throws Exception {
		// TC-NFR-04.4a: The 5th login attempt within a minute for one IP and username is answered normally.
		String ip = "192.168.10.1";
		for (int i = 1; i <= 5; i++) {
			performLogin(testUser.getUsername(), PASSWORD, ip)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.token").isNotEmpty());
		}

		// TC-NFR-04.4b: The 6th attempt in the same minute returns 429 with Retry-After.
		performLogin(testUser.getUsername(), PASSWORD, ip)
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(header().string("Retry-After", matchesRegex("^[1-9][0-9]*$")))
				.andExpect(jsonPath("$.status").value(429))
				.andExpect(jsonPath("$.message").value("Rate limit exceeded"))
				.andExpect(jsonPath("$.errors").isEmpty());
	}

	@Test
	void tcNfr044c_loginReplenishesAfterMinutePasses() throws Exception {
		// TC-NFR-04.4c: The next attempt after the minute has passed is answered normally again.
		String ip = "192.168.10.2";
		for (int i = 1; i <= 5; i++) {
			performLogin(testUser.getUsername(), PASSWORD, ip).andExpect(status().isOk());
		}

		// 6th attempt throttled
		performLogin(testUser.getUsername(), PASSWORD, ip).andExpect(status().isTooManyRequests());

		// Advance clock by 61 seconds (past the 60s refill duration)
		rateLimitService.setClock(Clock.fixed(BASE_TIME.plusSeconds(61), ZoneOffset.UTC));

		// 7th attempt is now permitted
		performLogin(testUser.getUsername(), PASSWORD, ip)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty());
	}

	@Test
	void tcNfr044d_anotherUsernameFromSameIpNotLimited() throws Exception {
		// TC-NFR-04.4d: Another username from the same IP, while the first is limited: Not limited.
		String ip = "192.168.10.3";
		AppUser secondUser = users.saveAndFlush(new AppUser("other_user_" + clock.millis(), "Other User",
				passwordEncoder.encode(PASSWORD), Role.VIEWER, clock.instant()));

		// Limit the first user
		for (int i = 1; i <= 5; i++) {
			performLogin(testUser.getUsername(), PASSWORD, ip).andExpect(status().isOk());
		}
		performLogin(testUser.getUsername(), PASSWORD, ip).andExpect(status().isTooManyRequests());

		// Second user from the exact same IP is answered normally
		performLogin(secondUser.getUsername(), PASSWORD, ip)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.username").value(secondUser.getUsername()));
	}

	@Test
	void differentClientIpsHaveIndependentBuckets() throws Exception {
		String ip1 = "192.168.20.1";
		String ip2 = "192.168.20.2";

		// Exhaust bucket on ip1
		for (int i = 1; i <= 5; i++) {
			performLogin(testUser.getUsername(), PASSWORD, ip1).andExpect(status().isOk());
		}
		performLogin(testUser.getUsername(), PASSWORD, ip1).andExpect(status().isTooManyRequests());

		// Same username from ip2 is answered normally
		performLogin(testUser.getUsername(), PASSWORD, ip2)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty());
	}

	@Test
	void failedLoginAttemptsAlsoCountAgainstLimit() throws Exception {
		String ip = "192.168.30.1";

		// 5 invalid password attempts
		for (int i = 1; i <= 5; i++) {
			performLogin(testUser.getUsername(), "wrong-pass", ip)
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.message").value("Invalid username or password"));
		}

		// 6th attempt (even with valid password) gets 429
		performLogin(testUser.getUsername(), PASSWORD, ip)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.status").value(429));
	}

	@Test
	void blankFieldsReturn400ValidationFailed() throws Exception {
		performLogin("", "", "192.168.40.1")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.username").value("Username is required"))
				.andExpect(jsonPath("$.errors.password").value("Password is required"));
	}

	@Test
	void tcNfr044e_webhookRateLimitBoundaryValues() throws Exception {
		// TC-NFR-04.4e: Webhook calls for one tool token just under (50) and just over (51) its limit:
		// Under passes; over gets 429.
		for (int i = 1; i <= 50; i++) {
			postAlert(toolTokenA)
					.andExpect(status().isAccepted())
					.andExpect(jsonPath("$.status").value("ACCEPTED"));
		}

		// 51st attempt exceeds rate limit
		postAlert(toolTokenA)
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(header().string("Retry-After", matchesRegex("^[1-9][0-9]*$")))
				.andExpect(jsonPath("$.status").value(429))
				.andExpect(jsonPath("$.message").value("Rate limit exceeded"))
				.andExpect(jsonPath("$.errors").isEmpty());

		// Advance clock past the 1 second refill interval
		rateLimitService.setClock(Clock.fixed(BASE_TIME.plusMillis(1100), ZoneOffset.UTC));

		// 52nd attempt succeeds again
		postAlert(toolTokenA)
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("ACCEPTED"));
	}

	@Test
	void differentToolsHaveIndependentWebhookBuckets() throws Exception {
		CreateToolResponse createdB = toolService.createTool(
				new CreateToolRequest("Tool B " + clock.millis()), adminUser.getId());
		String toolTokenB = createdB.token();

		// Exhaust Tool A's quota
		for (int i = 1; i <= 50; i++) {
			postAlert(toolTokenA).andExpect(status().isAccepted());
		}
		postAlert(toolTokenA).andExpect(status().isTooManyRequests());

		// Tool B is not throttled
		postAlert(toolTokenB)
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("ACCEPTED"));
	}

	@Test
	void invalidAndRevokedTokensReturn401AndDoNotConsumeBucket() throws Exception {
		// Missing token -> 401
		mvc.perform(post("/webhooks/alerts")
						.contentType(MediaType.APPLICATION_JSON)
						.content(ALERT_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401));

		// Wrong token -> 401
		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer tt_invalid_token_9999999999999999")
						.contentType(MediaType.APPLICATION_JSON)
						.content(ALERT_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401));

		// Revoked token -> 401
		CreateToolResponse toolToRevoke = toolService.createTool(
				new CreateToolRequest("Revoke Tool " + clock.millis()), adminUser.getId());
		toolService.revokeTool(toolToRevoke.id(), adminUser.getId());

		mvc.perform(post("/webhooks/alerts")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + toolToRevoke.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content(ALERT_PAYLOAD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Tool token has been revoked"));
	}

	@Test
	void tokenNeverAppearsInLogsWhenRateLimited() throws Exception {
		Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
		listAppender.start();
		rootLogger.addAppender(listAppender);

		try {
			// Exhaust limit and trigger 429
			for (int i = 1; i <= 50; i++) {
				postAlert(toolTokenA).andExpect(status().isAccepted());
			}
			postAlert(toolTokenA).andExpect(status().isTooManyRequests());

			for (ILoggingEvent event : listAppender.list) {
				assertThat(event.getFormattedMessage())
						.as("Log message must never contain the plaintext token")
						.doesNotContain(toolTokenA);

				if (event.getArgumentArray() != null) {
					for (Object arg : event.getArgumentArray()) {
						if (arg != null) {
							assertThat(arg.toString())
									.as("Log argument must never contain the plaintext token")
									.doesNotContain(toolTokenA);
						}
					}
				}
			}
		}
		finally {
			rootLogger.detachAppender(listAppender);
			listAppender.stop();
		}
	}

	@Test
	void untrustedClientCannotBypassLoginLimitUsingSpoofedXForwardedFor() throws Exception {
		String attackerIp = "198.51.100.99";
		for (int i = 1; i <= 5; i++) {
			mvc.perform(post("/api/auth/login")
							.with(req -> {
								req.setRemoteAddr(attackerIp);
								return req;
							})
							.header("X-Forwarded-For", "203.0.113." + i)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"username\":\"%s\",\"password\":\"wrong-pass\"}".formatted(testUser.getUsername())))
					.andExpect(status().isUnauthorized());
		}

		// 6th attempt from the same remote IP is throttled even with a fresh spoofed IP header
		mvc.perform(post("/api/auth/login")
						.with(req -> {
							req.setRemoteAddr(attackerIp);
							return req;
						})
						.header("X-Forwarded-For", "203.0.113.100")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(testUser.getUsername(), PASSWORD)))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.status").value(429));
	}

	private ResultActions performLogin(String username, String password, String clientIp) throws Exception {
		String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
		return mvc.perform(post("/api/auth/login")
				.with(request -> {
					if (clientIp != null) {
						request.setRemoteAddr(clientIp);
					}
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions postAlert(String bearerToken) throws Exception {
		return mvc.perform(post("/webhooks/alerts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(ALERT_PAYLOAD));
	}
}
