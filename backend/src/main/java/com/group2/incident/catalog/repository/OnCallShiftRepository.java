package com.group2.incident.catalog.repository;

import com.group2.incident.catalog.entity.OnCallShift;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OnCallShiftRepository extends JpaRepository<OnCallShift, Long> {

    List<OnCallShift> findByServiceIdOrderByStartTimeAsc(Long serviceId);

    void deleteByServiceId(Long serviceId);

    // A list, not a single result: if overlapping shifts ever reach the table, creating an incident
    // still works and takes the earliest shift.
    @Query("SELECT s FROM OnCallShift s LEFT JOIN FETCH s.responder WHERE s.service.id = :serviceId AND s.startTime <= :at AND s.endTime > :at ORDER BY s.startTime ASC, s.id ASC")
    List<OnCallShift> findActiveShiftsForService(@Param("serviceId") Long serviceId, @Param("at") Instant at);
}
