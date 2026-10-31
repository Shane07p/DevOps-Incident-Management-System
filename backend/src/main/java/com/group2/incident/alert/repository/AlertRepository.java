package com.group2.incident.alert.repository;

import com.group2.incident.alert.entity.Alert;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Alert a where a.source = :source and a.eventId = :eventId")
    Optional<Alert> findBySourceAndEventIdForUpdate(
            @Param("source") String source,
            @Param("eventId") String eventId);

    Optional<Alert> findBySourceAndEventId(String source, String eventId);

    @EntityGraph(attributePaths = {"service", "incident"})
    List<Alert> findAllByOrderByCreatedAtDescIdDesc();
}