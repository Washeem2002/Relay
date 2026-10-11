package com.sluice.admin.routers;

import com.sluice.admin.Change;
import com.sluice.admin.ConfigAdminService;
import com.sluice.schema.ApiKeyRecord;
import com.sluice.schema.TenantConfig;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/tenants")
public class TenantController {

    public record KeyView(String id, String prefix, java.time.Instant createdAt, boolean revoked) {
    }

    private final ConfigAdminService service;

    public TenantController(ConfigAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<TenantConfig> list() {
        return service.document().tenants().stream().map(TenantController::redacted).toList();
    }

    @GetMapping("/{id}")
    public TenantConfig get(@PathVariable String id) {
        return redacted(service.tenant(id));
    }

    @PostMapping
    public ResponseEntity<Change<TenantConfig>> create(
            @RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
            @RequestBody TenantConfig tenant) {
        return ResponseEntity.status(201).body(Change.of(service.createTenant(actor, tenant), redacted(tenant)));
    }

    @PutMapping("/{id}")
    public Change<TenantConfig> update(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                                       @PathVariable String id, @RequestBody TenantConfig tenant) {
        return Change.of(service.updateTenant(actor, id, tenant), redacted(tenant));
    }

    @DeleteMapping("/{id}")
    public Change<Void> delete(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                               @PathVariable String id) {
        return Change.of(service.deleteTenant(actor, id), null);
    }

    @GetMapping("/{id}/keys")
    public List<KeyView> keys(@PathVariable String id) {
        return service.tenant(id).apiKeys().stream()
                .map(key -> new KeyView(key.id(), key.prefix(), key.createdAt(), key.revoked()))
                .toList();
    }

    @PostMapping("/{id}/keys")
    public ResponseEntity<Map<String, Object>> createKey(
            @RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor, @PathVariable String id) {
        ConfigAdminService.CreatedKey created = service.createApiKey(actor, id);
        return ResponseEntity.status(201).body(Map.of(
                "keyId", created.keyId(),
                "apiKey", created.apiKey(),
                "prefix", created.prefix(),
                "note", "store this key now, it is not retrievable later"));
    }

    @DeleteMapping("/{id}/keys/{keyId}")
    public Change<Void> revokeKey(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                                  @PathVariable String id, @PathVariable String keyId) {
        return Change.of(service.revokeApiKey(actor, id, keyId), null);
    }

    private static TenantConfig redacted(TenantConfig tenant) {
        List<ApiKeyRecord> keys = tenant.apiKeys().stream()
                .map(key -> new ApiKeyRecord(key.id(), null, key.prefix(), key.createdAt(), key.revoked()))
                .toList();
        return tenant.withApiKeys(keys);
    }
}
