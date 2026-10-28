package com.group2.incident.incident.repository;

import com.group2.incident.incident.entity.EscalationStep;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationStepRepository extends JpaRepository<EscalationStep, Long> {
}
