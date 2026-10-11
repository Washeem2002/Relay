package com.sluice.admin.routers;

import com.sluice.admin.RuntimeInspectionService;
import com.sluice.config.AuditService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class RuntimeController {

    private final RuntimeInspectionService inspection;
    private final AuditService audit;

    public RuntimeController(RuntimeInspectionService inspection, AuditService audit) {
        this.inspection = inspection;
        this.audit = audit;
    }

    @GetMapping("/circuits")
    public List<RuntimeInspectionService.TargetView> circuits() {
        return inspection.circuits();
    }

    @GetMapping("/circuits/{pool}/{target}")
    public RuntimeInspectionService.TargetView circuit(@PathVariable String pool, @PathVariable String target) {
        return inspection.target(pool, target);
    }

    @PostMapping("/circuits/{pool}/{target}/reset")
    public RuntimeInspectionService.TargetView resetCircuit(@PathVariable String pool, @PathVariable String target) {
        RuntimeInspectionService.TargetView view = inspection.resetCircuit(pool, target);
        audit.record("admin", "CIRCUIT_RESET", "pool:" + pool, "manually reset circuit of target " + target);
        return view;
    }

    @GetMapping("/audit")
    public List<AuditService.AuditRecord> audit(@RequestParam(defaultValue = "100") int limit,
                                                @RequestParam(required = false) String action) {
        return audit.list(limit, action);
    }
}
