package com.sluice.admin.routers;

import com.sluice.admin.Change;
import com.sluice.admin.ConfigAdminService;
import com.sluice.admin.NotFoundException;
import com.sluice.schema.PlanConfig;
import com.sluice.schema.RateLimitPolicy;
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
public class PlanController {

    private final ConfigAdminService service;

    public PlanController(ConfigAdminService service) {
        this.service = service;
    }

    @GetMapping("/plans")
    public List<PlanConfig> list() {
        return service.document().plans();
    }

    @GetMapping("/plans/{name}")
    public PlanConfig get(@PathVariable String name) {
        return service.document().plans().stream()
                .filter(plan -> plan.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("plan " + name + " not found"));
    }

    @PostMapping("/plans")
    public ResponseEntity<Change<PlanConfig>> create(
            @RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor, @RequestBody PlanConfig plan) {
        return ResponseEntity.status(201).body(Change.of(service.createPlan(actor, plan), plan));
    }

    @PutMapping("/plans/{name}")
    public Change<PlanConfig> update(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                                     @PathVariable String name, @RequestBody PlanConfig plan) {
        return Change.of(service.updatePlan(actor, name, plan), plan);
    }

    @DeleteMapping("/plans/{name}")
    public Change<Void> delete(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                               @PathVariable String name) {
        return Change.of(service.deletePlan(actor, name), null);
    }

    @GetMapping("/global-limit")
    public RateLimitPolicy globalLimit() {
        return service.document().globalLimit();
    }

    @PutMapping("/global-limit")
    public Change<RateLimitPolicy> setGlobalLimit(
            @RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
            @RequestBody RateLimitPolicy policy) {
        return Change.of(service.setGlobalLimit(actor, policy), policy);
    }

    @DeleteMapping("/global-limit")
    public Change<Void> clearGlobalLimit(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor) {
        return Change.of(service.setGlobalLimit(actor, null), null);
    }
}
