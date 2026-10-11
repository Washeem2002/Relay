package com.sluice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sluice.schema.ConfigDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ConfigLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigLoader.class);

    private final ConfigService configService;
    private final ConfigReloader reloader;
    private final SluiceProperties properties;
    private final ObjectMapper mapper;

    public ConfigLoader(ConfigService configService, ConfigReloader reloader, SluiceProperties properties,
                        ObjectMapper mapper) {
        this.configService = configService;
        this.reloader = reloader;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedFromBootstrapFile();
        reloader.reloadLatest();
    }

    private void seedFromBootstrapFile() {
        String path = properties.config().bootstrapPath();
        if (path.isBlank()) {
            return;
        }
        try {
            if (configService.latestVersion() > 0) {
                return;
            }
            ConfigDocument seed = mapper.readValue(Files.readString(Path.of(path)), ConfigDocument.class);
            configService.mutate("bootstrap", "BOOTSTRAP", path, "seeded from bootstrap file",
                    current -> current.equals(ConfigDocument.empty()) ? seed : current);
        } catch (IOException | RuntimeException e) {
            LOG.error("bootstrap config {} could not be applied: {}", path, e.getMessage());
        }
    }
}
