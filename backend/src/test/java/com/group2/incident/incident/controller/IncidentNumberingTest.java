package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each org numbers its incidents from INC-1 (D48). Runs outside the test transaction, so each request
 * opens its session in its own org, as in production.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IncidentNumberingTest {

	@Autowired
	private TestOrgs orgs;

	private SignedUp acme;

	private SignedUp globex;

	@AfterEach
	void deleteOrgs() {
		if (acme != null) {
			orgs.delete(acme.orgId());
		}
		if (globex != null) {
			orgs.delete(globex.orgId());
		}
	}

	@Test
	void eachOrgStartsAtInc1AndCountsOnItsOwn() throws Exception {
		acme = orgs.signUp("numbers-acme@acme.test", "Numbers Acme");
		globex = orgs.signUp("numbers-globex@globex.test", "Numbers Globex");
		long acmeService = orgs.createService(acme.adminToken(), "Payments", acme.adminId());
		long globexService = orgs.createService(globex.adminToken(), "Payments", globex.adminId());

		String acmeFirst = reference(orgs.declareIncident(acme.adminToken(), acmeService, "First"));
		String acmeSecond = reference(orgs.declareIncident(acme.adminToken(), acmeService, "Second"));
		String globexFirst = reference(orgs.declareIncident(globex.adminToken(), globexService, "First"));

		assertThat(acmeFirst).isEqualTo("INC-1");
		assertThat(acmeSecond).isEqualTo("INC-2");
		assertThat(globexFirst).isEqualTo("INC-1");
	}

	private static String reference(String incidentJson) {
		return JsonPath.read(incidentJson, "$.reference");
	}

}
