package com.group2.incident.support;

import com.group2.incident.common.ratelimit.RateLimitService;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Resets in-memory rate-limit buckets before each test method so that sequential tests in the
 * same suite running from the same client IP do not interfere with each other.
 *
 * <p><strong>Note on Parallel Execution:</strong> This listener clears the shared singleton
 * {@link RateLimitService} state between test methods for deterministic sequential execution.
 * If tests are configured to run concurrently in parallel threads sharing the same Spring
 * application context, resetting the singleton state or sharing client IPs/tokens across threads
 * would introduce race conditions. For parallel test execution, tests must either run in separate
 * application contexts or use isolated per-thread identifiers.
 */
public class RateLimitResetTestExecutionListener extends AbstractTestExecutionListener {

	@Override
	public void beforeTestMethod(TestContext testContext) {
		if (testContext.getApplicationContext().containsBean("rateLimitService")) {
			testContext.getApplicationContext().getBean(RateLimitService.class).clear();
		}
	}
}
