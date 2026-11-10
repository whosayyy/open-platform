package com.yin.ai.platform.gateway.config;

import com.yin.ai.platform.gateway.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfiguration {

    @Bean
    public RateLimitConfig rateLimitConfig(GatewayProperties properties) {
        return properties.getRateLimit() != null
                ? properties.getRateLimit()
                : new RateLimitConfig();
    }
}
