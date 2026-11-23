package com.settle.incident.repository;

import com.settle.incident.entity.EscalationStep;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationStepRepository extends JpaRepository<EscalationStep, Long> {
}
