package com.sluice.admin.routers;

import com.sluice.admin.Change;
import com.sluice.admin.ConfigAdminService;
import com.sluice.schema.PoolConfig;
import com.sluice.schema.TargetConfig;
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
@RequestMapping("/admin/pools")
public class PoolController {

    private final ConfigAdminService service;

    public PoolController(ConfigAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<PoolConfig> list() {
        return service.document().pools();
    }

    @GetMapping("/{name}")
    public PoolConfig get(@PathVariable String name) {
        return service.pool(name);
    }

    @PostMapping
    public ResponseEntity<Change<PoolConfig>> create(
            @RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor, @RequestBody PoolConfig pool) {
        return ResponseEntity.status(201).body(Change.of(service.createPool(actor, pool), pool));
    }

    @PutMapping("/{name}")
    public Change<PoolConfig> update(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                                     @PathVariable String name, @RequestBody PoolConfig pool) {
        return Change.of(service.updatePool(actor, name, pool), pool);
    }

    @DeleteMapping("/{name}")
    public Change<Void> delete(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                               @PathVariable String name) {
        return Change.of(service.deletePool(actor, name), null);
    }

    @GetMapping("/{name}/targets")
    public List<TargetConfig> targets(@PathVariable String name) {
        return service.pool(name).targets();
    }

    @PostMapping("/{name}/targets")
    public ResponseEntity<Change<TargetConfig>> addTarget(
            @RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
            @PathVariable String name, @RequestBody TargetConfig target) {
        return ResponseEntity.status(201).body(Change.of(service.addTarget(actor, name, target), target));
    }

    @PutMapping("/{name}/targets/{targetId}")
    public Change<TargetConfig> updateTarget(
            @RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
            @PathVariable String name, @PathVariable String targetId, @RequestBody TargetConfig target) {
        return Change.of(service.updateTarget(actor, name, targetId, target), target);
    }

    @DeleteMapping("/{name}/targets/{targetId}")
    public Change<Void> removeTarget(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                                     @PathVariable String name, @PathVariable String targetId) {
        return Change.of(service.removeTarget(actor, name, targetId), null);
    }
}
