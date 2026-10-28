package com.group2.incident.catalog.repository;

import com.group2.incident.catalog.entity.OnCallShift;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OnCallShiftRepository extends JpaRepository<OnCallShift, Long> {

    List<OnCallShift> findByServiceIdOrderByStartTimeAsc(Long serviceId);

    void deleteByServiceId(Long serviceId);

    @Query("SELECT s FROM OnCallShift s LEFT JOIN FETCH s.responder WHERE s.service.id = :serviceId AND s.startTime <= :at AND s.endTime > :at")
    Optional<OnCallShift> findActiveShiftForService(@Param("serviceId") Long serviceId, @Param("at") Instant at);
}
