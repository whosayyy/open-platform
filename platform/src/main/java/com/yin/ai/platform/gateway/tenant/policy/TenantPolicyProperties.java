package com.yin.ai.platform.gateway.tenant.policy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "ai.gateway.tenant-policy")
public class TenantPolicyProperties {

    private Set<String> platformAllowedFunctions = new LinkedHashSet<>();
    private Set<String> safeSystemFunctions = new LinkedHashSet<>(Set.of(
            "embedding", "recallMemory", "searchNews", "readWebPage"));
    private Duration concurrencyLease = Duration.ofMinutes(10);
}
