package com.sluice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class ConfigSubscriptionConfig {

    @Bean
    public RedisMessageListenerContainer configUpdateListenerContainer(RedisConnectionFactory connectionFactory,
                                                                      ConfigReloader reloader,
                                                                      SluiceProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> reloader.reloadLatest(),
                new ChannelTopic(properties.config().channel()));
        return container;
    }
}
