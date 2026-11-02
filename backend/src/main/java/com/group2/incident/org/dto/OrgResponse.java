package com.group2.incident.org.dto;

import com.group2.incident.org.entity.Organization;

public record OrgResponse(Long id, String name, String slug) {

	public static OrgResponse from(Organization org) {
		return new OrgResponse(org.getId(), org.getName(), org.getSlug());
	}

}
