package com.sluice.admin.routers;

import com.sluice.admin.ReadinessService;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final ReadinessService readiness;
    private final PrometheusMeterRegistry registry;

    public HealthController(ReadinessService readiness, PrometheusMeterRegistry registry) {
        this.readiness = readiness;
        this.registry = registry;
    }

    @GetMapping("/healthz")
    public Map<String, String> liveness() {
        return Map.of("status", "alive");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> readiness() {
        ReadinessService.Report report = readiness.check();
        return ResponseEntity.status(report.ready() ? 200 : 503)
                .body(Map.of("status", report.ready() ? "ready" : "not_ready", "checks", report.checks()));
    }

    @GetMapping(value = "/metrics", produces = "text/plain;version=0.0.4;charset=utf-8")
    public String metrics() {
        return registry.scrape();
    }

    @GetMapping(value = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> root() {
        return Map.of("service", "sluice-admin");
    }
}
