package com.group2.incident.support;

import com.group2.incident.common.tenancy.OrgContext;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Puts every test in one committed "Test org" before its transaction starts (Hibernate reads the
 * org when a session opens). Tests that need a second org create it themselves.
 */
public class TestOrgExecutionListener extends AbstractTestExecutionListener {

	public static final String TEST_ORG_SLUG = "test-org";

	@Override
	public int getOrder() {
		return 3500;
	}

	@Override
	public void beforeTestMethod(TestContext testContext) {
		JdbcTemplate jdbc = testContext.getApplicationContext().getBean(JdbcTemplate.class);
		jdbc.update("INSERT INTO organization (name, slug, created_at) VALUES ('Test org', ?, now()) ON CONFLICT (slug) DO NOTHING",
				TEST_ORG_SLUG);
		Long orgId = jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, TEST_ORG_SLUG);
		OrgContext.set(orgId);
	}

	@Override
	public void afterTestMethod(TestContext testContext) {
		OrgContext.clear();
	}

}
