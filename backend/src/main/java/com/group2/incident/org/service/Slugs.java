package com.group2.incident.org.service;

import java.text.Normalizer;
import java.util.Locale;

/** Org names to URL slugs: lower-case letters, digits, and single hyphens, at most 50 characters. */
public final class Slugs {

	private static final int MAX = 50;

	private Slugs() {
	}

	public static String from(String name) {
		String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
		if (slug.length() > MAX) {
			slug = slug.substring(0, MAX).replaceAll("-+$", "");
		}
		return slug.isEmpty() ? "org" : slug;
	}

}
