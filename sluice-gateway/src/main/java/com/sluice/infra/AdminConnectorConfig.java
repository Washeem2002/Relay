package com.sluice.infra;

import com.sluice.config.SluiceProperties;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminConnectorConfig {

    private static final String ADMIN_MAX_HEADER_BYTES = "16384";

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> adminConnectorCustomizer(
            SluiceProperties properties) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(properties.admin().port());
            connector.setProperty("maxHttpHeaderSize", ADMIN_MAX_HEADER_BYTES);
            connector.setProperty("connectionTimeout", "20000");
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
