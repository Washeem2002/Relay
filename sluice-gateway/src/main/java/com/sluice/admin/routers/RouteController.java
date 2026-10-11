package com.sluice.admin.routers;

import com.sluice.admin.Change;
import com.sluice.admin.ConfigAdminService;
import com.sluice.config.ConfigService;
import com.sluice.schema.CanaryConfig;
import com.sluice.schema.RouteConfig;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/routes")
public class RouteController {

    public record CanaryWeightRequest(int weight) {
    }

    private final ConfigAdminService service;

    public RouteController(ConfigAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<RouteConfig> list() {
        return service.document().routes();
    }

    @GetMapping("/{name}")
    public RouteConfig get(@PathVariable String name) {
        return service.route(name);
    }

    @PostMapping
    public ResponseEntity<Change<RouteConfig>> create(
            @RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
            @RequestBody RouteConfig route) {
        ConfigService.MutationResult result = service.createRoute(actor, route);
        return ResponseEntity.status(201).body(Change.of(result, route));
    }

    @PutMapping("/{name}")
    public Change<RouteConfig> update(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                                      @PathVariable String name, @RequestBody RouteConfig route) {
        return Change.of(service.updateRoute(actor, name, route), route);
    }

    @DeleteMapping("/{name}")
    public Change<Void> delete(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                               @PathVariable String name) {
        return Change.of(service.deleteRoute(actor, name), null);
    }

    @PutMapping("/{name}/canary")
    public Change<CanaryConfig> setCanary(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                                          @PathVariable String name, @RequestBody CanaryConfig canary) {
        return Change.of(service.setCanary(actor, name, canary), canary);
    }

    @PatchMapping("/{name}/canary")
    public Change<CanaryWeightRequest> setCanaryWeight(
            @RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
            @PathVariable String name, @RequestBody CanaryWeightRequest request) {
        return Change.of(service.setCanaryWeight(actor, name, request.weight()), request);
    }

    @DeleteMapping("/{name}/canary")
    public Change<Void> clearCanary(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                                    @PathVariable String name) {
        return Change.of(service.clearCanary(actor, name), null);
    }
}
