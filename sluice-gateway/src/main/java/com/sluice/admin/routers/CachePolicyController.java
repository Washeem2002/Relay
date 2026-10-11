package com.sluice.admin.routers;

import com.sluice.admin.CacheAdminService;
import com.sluice.admin.Change;
import com.sluice.admin.ConfigAdminService;
import com.sluice.admin.NotFoundException;
import com.sluice.schema.CachePolicyConfig;
import java.util.List;
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
@RequestMapping("/admin")
public class CachePolicyController {

    private final ConfigAdminService service;
    private final CacheAdminService cacheAdmin;

    public CachePolicyController(ConfigAdminService service, CacheAdminService cacheAdmin) {
        this.service = service;
        this.cacheAdmin = cacheAdmin;
    }

    @GetMapping("/cache-policies")
    public List<CachePolicyConfig> list() {
        return service.document().cachePolicies();
    }

    @GetMapping("/cache-policies/{name}")
    public CachePolicyConfig get(@PathVariable String name) {
        return service.document().cachePolicies().stream()
                .filter(policy -> policy.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("cache policy " + name + " not found"));
    }

    @PostMapping("/cache-policies")
    public ResponseEntity<Change<CachePolicyConfig>> create(
            @RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
            @RequestBody CachePolicyConfig policy) {
        return ResponseEntity.status(201).body(Change.of(service.createCachePolicy(actor, policy), policy));
    }

    @PutMapping("/cache-policies/{name}")
    public Change<CachePolicyConfig> update(
            @RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
            @PathVariable String name, @RequestBody CachePolicyConfig policy) {
        return Change.of(service.updateCachePolicy(actor, name, policy), policy);
    }

    @DeleteMapping("/cache-policies/{name}")
    public Change<Void> delete(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                               @PathVariable String name) {
        return Change.of(service.deleteCachePolicy(actor, name), null);
    }

    @PostMapping("/cache/purge")
    public CacheAdminService.PurgeResult purge(@RequestBody CacheAdminService.PurgeRequest request) {
        return cacheAdmin.purge(request);
    }
}
