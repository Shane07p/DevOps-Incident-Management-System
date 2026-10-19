package com.group2.incident.catalog.repository;

import com.group2.incident.catalog.entity.ServiceSlaTarget;
import com.group2.incident.catalog.entity.ServiceSlaTargetId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ServiceSlaTargetRepository extends JpaRepository<ServiceSlaTarget, ServiceSlaTargetId> {

    @Query("select t from ServiceSlaTarget t where t.service.id = :serviceId")
    List<ServiceSlaTarget> findByServiceId(@Param("serviceId") Long serviceId);
}
