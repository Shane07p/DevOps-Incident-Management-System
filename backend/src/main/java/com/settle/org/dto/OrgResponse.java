package com.settle.org.dto;

import com.settle.org.entity.Organization;

public record OrgResponse(Long id, String name, String slug) {

	public static OrgResponse from(Organization org) {
		return new OrgResponse(org.getId(), org.getName(), org.getSlug());
	}

}
