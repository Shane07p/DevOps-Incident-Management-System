package com.group2.incident.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OrgContextTest {

	private final OrgTenantResolver resolver = new OrgTenantResolver();

	@AfterEach
	void clear() {
		OrgContext.clear();
	}

	@Test
	void withNoOrgTheResolverAnswersNoneWhichMatchesNoRow() {
		assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(OrgContext.NONE);
		assertThat(resolver.isRoot(OrgContext.NONE)).isFalse();
	}

	@Test
	void runAsSetsTheOrgAndRestoresThePreviousOne() {
		OrgContext.set(7L);
		long inside = OrgContext.runAs(9L, OrgContext::current);
		assertThat(inside).isEqualTo(9L);
		assertThat(OrgContext.current()).isEqualTo(7L);
	}

	@Test
	void runAsRootIsRootAndRestores() {
		long inside = OrgContext.runAsRoot(OrgContext::current);
		assertThat(resolver.isRoot(inside)).isTrue();
		assertThat(OrgContext.current()).isEqualTo(OrgContext.NONE);
	}

	@Test
	void restoresEvenWhenTheWorkThrows() {
		OrgContext.set(3L);
		try {
			OrgContext.runAs(4L, () -> {
				throw new IllegalStateException("boom");
			});
		}
		catch (IllegalStateException expected) {
			// restored below
		}
		assertThat(OrgContext.current()).isEqualTo(3L);
	}

}
