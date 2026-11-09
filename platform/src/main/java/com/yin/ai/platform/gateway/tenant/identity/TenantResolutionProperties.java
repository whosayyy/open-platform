package com.yin.ai.platform.gateway.tenant.identity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.gateway.tenant-resolution")
public class TenantResolutionProperties {

    private boolean userIdFallbackEnabled = true;
    private String jwtClaimName = "tenant_id";
}
