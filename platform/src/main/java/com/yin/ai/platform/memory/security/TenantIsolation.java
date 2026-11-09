package com.yin.ai.platform.memory.security;

/**
 * 租户隔离 SPI — 为多租户环境生成隔离 key，防止跨租户数据访问。
 * <p>
 * 隔离策略：
 * <ul>
 *   <li>PREFIX — 在 key 前添加 "tenant:{tenantId}:" 前缀（推荐，简单高效）</li>
 *   <li>SCHEMA — 使用不同的数据库 Schema（MySQL）</li>
 *   <li>COLUMN — 在所有表添加 tenant_id 列过滤</li>
 * </ul>
 * <p>
 * 本接口定义 PREFIX 策略的默认方法，子类可按需覆盖。
 */
public interface TenantIsolation {

    /** 生成隔离后的 key */
    String isolateKey(String tenantId, String rawKey);

    /** 从已隔离的 key 中提取 tenantId */
    String extractTenantId(String isolatedKey);

    /** 判断两个 key 是否属于同一租户 */
    boolean isSameTenant(String key1, String key2);

    /** 从 key 中移除租户前缀 */
    String stripTenantPrefix(String isolatedKey);
}
