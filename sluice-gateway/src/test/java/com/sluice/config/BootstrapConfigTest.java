package com.sluice.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sluice.schema.ConfigDocument;
import com.sluice.support.TestProps;
import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class BootstrapConfigTest {

    @Test
    void demoBootstrapFileIsStrictlyParseableAndValid() throws IOException {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        ConfigDocument document = mapper.readValue(new File("../demo/bootstrap.json"), ConfigDocument.class);
        assertThat(new ConfigValidator(TestProps.create()).validate(document)).isEmpty();
        assertThat(document.pools()).hasSize(2);
        assertThat(document.routes()).extracting(route -> route.name()).containsExactly("demo", "content");
        assertThat(document.findTenant("demo")).isPresent();
    }
}
