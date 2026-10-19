package com.group2.incident.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Embeddable
@Getter
@Setter
public class ServiceSlaTargetId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "service_id")
    private Long service;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 10, nullable = false)
    private Severity severity;

    public ServiceSlaTargetId() {
    }

    public ServiceSlaTargetId(Severity severity) {
        this.severity = severity;
    }

    public ServiceSlaTargetId(Long service, Severity severity) {
        this.service = service;
        this.severity = severity;
    }

    public Long getServiceId() {
        return service;
    }

    public void setServiceId(Long serviceId) {
        this.service = serviceId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceSlaTargetId that = (ServiceSlaTargetId) o;
        return Objects.equals(service, that.service) && severity == that.severity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(service, severity);
    }

    @Override
    public String toString() {
        return "ServiceSlaTargetId{" +
                "service=" + service +
                ", severity=" + severity +
                '}';
    }
}
