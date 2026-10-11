package com.sluice.admin.routers;

import com.sluice.admin.Change;
import com.sluice.admin.ConfigAdminService;
import com.sluice.admin.RuntimeInspectionService;
import com.sluice.config.ConfigReloader;
import com.sluice.config.ConfigService;
import com.sluice.config.ConfigValidator;
import com.sluice.schema.ConfigDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class ConfigController {

    private final ConfigService configService;
    private final ConfigAdminService adminService;
    private final ConfigValidator validator;
    private final ConfigReloader reloader;
    private final RuntimeInspectionService inspection;

    public ConfigController(ConfigService configService, ConfigAdminService adminService, ConfigValidator validator,
                            ConfigReloader reloader, RuntimeInspectionService inspection) {
        this.configService = configService;
        this.adminService = adminService;
        this.validator = validator;
        this.reloader = reloader;
        this.inspection = inspection;
    }

    @GetMapping("/config")
    public ConfigService.VersionedConfig current() {
        return configService.latest().orElse(new ConfigService.VersionedConfig(0, ConfigDocument.empty()));
    }

    @PutMapping("/config")
    public Change<ConfigDocument> replace(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                                          @RequestBody ConfigDocument document) {
        return Change.of(adminService.replaceAll(actor, document), document);
    }

    @PostMapping("/config/validate")
    public Map<String, Object> validate(@RequestBody ConfigDocument document) {
        List<String> errors = validator.validate(document);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", errors.isEmpty());
        body.put("errors", errors);
        return body;
    }

    @GetMapping("/config/versions")
    public List<ConfigService.VersionSummary> versions(@RequestParam(defaultValue = "50") int limit) {
        return configService.history(limit);
    }

    @GetMapping("/config/versions/{version}")
    public ConfigService.VersionedConfig version(@PathVariable long version) {
        return configService.get(version).orElseThrow(() ->
                new com.sluice.admin.NotFoundException("config version " + version + " does not exist"));
    }

    @PostMapping("/config/rollback/{version}")
    public Map<String, Object> rollback(@RequestHeader(value = "X-Admin-Actor", defaultValue = "admin") String actor,
                                        @PathVariable long version) {
        ConfigService.MutationResult result = configService.rollbackTo(actor, version);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("restoredFrom", version);
        body.put("configVersion", result.config().version());
        body.put("changed", result.changed());
        return body;
    }

    @PostMapping("/config/reload")
    public Map<String, Object> reload() {
        ConfigReloader.Outcome outcome = reloader.forceReload();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", outcome);
        body.put("activeVersion", inspection.snapshot().version());
        body.put("lastError", reloader.lastError());
        return body;
    }

    @GetMapping("/snapshot")
    public RuntimeInspectionService.SnapshotDump snapshot() {
        return inspection.snapshot();
    }
}
