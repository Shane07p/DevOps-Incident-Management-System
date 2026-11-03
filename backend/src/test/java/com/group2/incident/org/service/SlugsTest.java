package com.group2.incident.org.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlugsTest {

	@Test
	void lowerCasesAndJoinsWordsWithHyphens() {
		assertThat(Slugs.from("DA-IICT Group 2")).isEqualTo("da-iict-group-2");
	}

	@Test
	void dropsAccentsAndOtherCharactersAndTrimsHyphens() {
		assertThat(Slugs.from("  Café & Co.!  ")).isEqualTo("cafe-co");
	}

	@Test
	void anEmptyResultBecomesOrg() {
		assertThat(Slugs.from("!!!")).isEqualTo("org");
	}

	@Test
	void capsAtFiftyCharacters() {
		assertThat(Slugs.from("a".repeat(80))).hasSize(50);
	}

}
