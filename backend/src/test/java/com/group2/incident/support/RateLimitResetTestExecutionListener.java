package com.group2.incident.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.group2.incident.common.ratelimit.RateLimitService;
import java.util.List;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Starts every test with empty rate-limit buckets: the whole suite logs in from the same address, many
 * times a minute. Sequential tests only; parallel tests sharing one context would clear each other's.
 */
public class RateLimitResetTestExecutionListener extends AbstractTestExecutionListener {

	@Override
	public void beforeTestMethod(TestContext testContext) {
		RateLimitService service = testContext.getApplicationContext().getBean(RateLimitService.class);
		for (String buckets : List.of("loginBuckets", "webhookBuckets")) {
			((Cache<?, ?>) ReflectionTestUtils.getField(service, buckets)).invalidateAll();
		}
	}
}
