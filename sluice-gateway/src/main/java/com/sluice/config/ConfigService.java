package com.sluice.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sluice.model.ConfigVersionEntity;
import com.sluice.repository.ConfigVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.UnaryOperator;
import com.sluice.schema.ConfigDocument;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ConfigService {

    private static final int MAX_ATTEMPTS = 5;

    public record VersionedConfig(long version, ConfigDocument document) {
    }

    public record VersionSummary(long version, Instant createdAt, String author, String action, String comment) {
    }

    public record MutationResult(VersionedConfig config, boolean changed) {
    }

    private final ConfigVersionRepository versions;
    private final AuditService audit;
    private final ConfigValidator validator;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ConfigService(ConfigVersionRepository versions, AuditService audit, ConfigValidator validator,
                         ObjectMapper mapper, TransactionTemplate transactions, ApplicationEventPublisher events,
                         Clock clock) {
        this.versions = versions;
        this.audit = audit;
        this.validator = validator;
        this.mapper = mapper;
        this.transactions = transactions;
        this.events = events;
        this.clock = clock;
    }

    public Optional<VersionedConfig> latest() {
        return transactions.execute(status -> versions.findTopByOrderByVersionDesc().map(this::toVersioned));
    }

    public long latestVersion() {
        Long max = transactions.execute(status -> versions.findMaxVersion());
        return max == null ? 0 : max;
    }

    public Optional<VersionedConfig> get(long version) {
        return transactions.execute(status -> versions.findById(version).map(this::toVersioned));
    }

    public List<VersionSummary> history(int limit) {
        List<ConfigVersionEntity> rows = transactions.execute(status ->
                versions.findAllByOrderByVersionDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500)))));
        return rows.stream()
                .map(row -> new VersionSummary(row.getVersion(), row.getCreatedAt(), row.getAuthor(), row.getAction(),
                        row.getComment()))
                .toList();
    }

    public MutationResult mutate(String actor, String action, String target, String comment,
                                 UnaryOperator<ConfigDocument> mutation) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                MutationResult result = transactions.execute(status ->
                        applyMutation(actor, action, target, comment, mutation));
                if (result.changed()) {
                    events.publishEvent(new ConfigChangedEvent(result.config().version()));
                }
                return result;
            } catch (DataIntegrityViolationException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("config changed concurrently, retry the request", e);
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    public MutationResult rollbackTo(String actor, long targetVersion) {
        ConfigDocument snapshot = get(targetVersion)
                .orElseThrow(() -> new NoSuchElementException("config version " + targetVersion + " does not exist"))
                .document();
        return mutate(actor, "ROLLBACK", "version:" + targetVersion, "rollback to version " + targetVersion,
                current -> snapshot);
    }

    private MutationResult applyMutation(String actor, String action, String target, String comment,
                                         UnaryOperator<ConfigDocument> mutation) {
        Optional<ConfigVersionEntity> latest = versions.findTopByOrderByVersionDesc();
        long currentVersion = latest.map(ConfigVersionEntity::getVersion).orElse(0L);
        ConfigDocument current = latest.map(row -> read(row.getDocument())).orElse(ConfigDocument.empty());
        ConfigDocument updated = mutation.apply(current);
        if (updated.equals(current)) {
            return new MutationResult(new VersionedConfig(currentVersion, current), false);
        }
        validator.validateOrThrow(updated);
        long nextVersion = currentVersion + 1;
        versions.saveAndFlush(new ConfigVersionEntity(nextVersion, write(updated), Instant.now(clock), actor, action,
                comment));
        audit.record(actor, action, target, comment == null ? "config version " + nextVersion
                : comment + " (config version " + nextVersion + ")");
        return new MutationResult(new VersionedConfig(nextVersion, updated), true);
    }

    private VersionedConfig toVersioned(ConfigVersionEntity entity) {
        return new VersionedConfig(entity.getVersion(), read(entity.getDocument()));
    }

    private ConfigDocument read(String json) {
        try {
            return mapper.readValue(json, ConfigDocument.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored config is unreadable", e);
        }
    }

    private String write(ConfigDocument document) {
        try {
            return mapper.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("config cannot be serialized", e);
        }
    }
}
