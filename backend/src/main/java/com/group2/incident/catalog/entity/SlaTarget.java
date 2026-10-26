package com.group2.incident.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlaTarget {

    @Column(name = "response_minutes", nullable = false)
    private int responseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    public SlaTarget(int responseMinutes, int resolutionMinutes) {
        this.responseMinutes = responseMinutes;
        this.resolutionMinutes = resolutionMinutes;
    }
}
