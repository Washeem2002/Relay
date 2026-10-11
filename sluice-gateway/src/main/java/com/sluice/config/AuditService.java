package com.sluice.config;

import com.sluice.model.AuditLogEntity;
import com.sluice.repository.AuditLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    public record AuditRecord(long id, Instant at, String actor, String action, String target, String detail) {
    }

    private final AuditLogRepository repository;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void record(String actor, String action, String target, String detail) {
        repository.save(new AuditLogEntity(Instant.now(clock), actor, action, target, detail));
    }

    public List<AuditRecord> list(int limit, String action) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 1000)));
        List<AuditLogEntity> rows = action == null || action.isBlank()
                ? repository.findAllByOrderByIdDesc(page)
                : repository.findByActionOrderByIdDesc(action, page);
        return rows.stream()
                .map(row -> new AuditRecord(row.getId(), row.getAt(), row.getActor(), row.getAction(), row.getTarget(),
                        row.getDetail()))
                .toList();
    }
}
