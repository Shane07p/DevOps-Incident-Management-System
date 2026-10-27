package com.group2.incident.catalog.repository;

import com.group2.incident.catalog.entity.ServiceEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ServiceRepository extends JpaRepository<ServiceEntity, Long> {

    List<ServiceEntity> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    @Query("select distinct s from ServiceEntity s left join fetch s.targets left join fetch s.owner left join fetch s.fallbackContact where s.id = :id")
    Optional<ServiceEntity> findWithDetailsById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ServiceEntity s where lower(s.name) = lower(:name)")
    Optional<ServiceEntity> findByNameIgnoreCaseForUpdate(@Param("name") String name);
}
