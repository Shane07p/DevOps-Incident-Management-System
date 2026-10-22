package com.group2.incident.catalog.entity;

import com.group2.incident.auth.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "on_call_shift")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OnCallShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceEntity service;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "responder_id", nullable = false)
    private AppUser responder;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    public OnCallShift(ServiceEntity service, AppUser responder, Instant startTime, Instant endTime) {
        this.service = service;
        this.responder = responder;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public OnCallShift(Long id, ServiceEntity service, AppUser responder, Instant startTime, Instant endTime) {
        this.id = id;
        this.service = service;
        this.responder = responder;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}
