package com.group2.incident.catalog;

/** One severity's targets in a request or response. In a request, a null response target takes the default. */
public record SlaTargetDto(Integer responseMinutes, Integer resolutionMinutes) {

	static SlaTargetDto from(SlaTarget target) {
		return new SlaTargetDto(target.responseMinutes(), target.resolutionMinutes());
	}

}
