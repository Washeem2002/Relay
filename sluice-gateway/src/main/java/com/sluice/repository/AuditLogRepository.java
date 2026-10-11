package com.sluice.repository;

import com.sluice.model.AuditLogEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findAllByOrderByIdDesc(Pageable pageable);

    List<AuditLogEntity> findByActionOrderByIdDesc(String action, Pageable pageable);
}
