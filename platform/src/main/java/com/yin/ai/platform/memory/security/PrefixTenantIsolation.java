package com.yin.ai.platform.memory.security;

/**
 * 前缀租户隔离 — 在 Redis/DB key 前添加 "tenant:{tenantId}:" 前缀。
 * <p>
 * 示例：rawKey="ai:memory:ep:user-1" + tenantId="t-123"
 * → isolatedKey="tenant:t-123:ai:memory:ep:user-1"
 */
public class PrefixTenantIsolation implements TenantIsolation {

    private static final String TENANT_PREFIX = "tenant:";

    @Override
    public String isolateKey(String tenantId, String rawKey) {
        if (tenantId == null || tenantId.isBlank()) {
            return rawKey;
        }
        return TENANT_PREFIX + tenantId + ":" + rawKey;
    }

    @Override
    public String extractTenantId(String isolatedKey) {
        if (isolatedKey == null || !isolatedKey.startsWith(TENANT_PREFIX)) {
            return "";
        }
        String rest = isolatedKey.substring(TENANT_PREFIX.length());
        int colon = rest.indexOf(':');
        return colon > 0 ? rest.substring(0, colon) : rest;
    }

    @Override
    public boolean isSameTenant(String key1, String key2) {
        return extractTenantId(key1).equals(extractTenantId(key2));
    }

    @Override
    public String stripTenantPrefix(String isolatedKey) {
        if (isolatedKey == null || !isolatedKey.startsWith(TENANT_PREFIX)) {
            return isolatedKey;
        }
        String rest = isolatedKey.substring(TENANT_PREFIX.length());
        int colon = rest.indexOf(':');
        return colon > 0 ? rest.substring(colon + 1) : rest;
    }
}
