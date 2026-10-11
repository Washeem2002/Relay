package com.sluice.repository;

import com.sluice.model.ConfigVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConfigVersionRepository extends JpaRepository<ConfigVersionEntity, Long> {

    Optional<ConfigVersionEntity> findTopByOrderByVersionDesc();

    List<ConfigVersionEntity> findAllByOrderByVersionDesc(Pageable pageable);

    @Query("select max(v.version) from ConfigVersionEntity v")
    Long findMaxVersion();
}
