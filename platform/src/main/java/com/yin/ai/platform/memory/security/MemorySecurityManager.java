package com.yin.ai.platform.memory.security;

import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.security.MemoryScope;
import com.yin.ai.platform.memory.multiagent.AccessControl;
import com.yin.ai.platform.memory.multiagent.SharedMemoryManager;

/**
 * Memory 安全管理器 — 组合 Tenant 隔离 + Access 控制，提供统一的安全检查入口。
 * <p>
 * 职责：
 * <ul>
 *   <li>将 MemoryId 转换为租户隔离的存储 key（委托 {@link TenantIsolation}）</li>
 *   <li>在执行读写操作前进行权限裁决（委托 {@link AccessControl}）</li>
 *   <li>提供 scope-aware key 解析（委托 {@link MemoryScope}）</li>
 * </ul>
 */
public class MemorySecurityManager {

    private final TenantIsolation tenantIsolation;
    private final AccessControl accessControl;

    public MemorySecurityManager(TenantIsolation tenantIsolation,
                                  AccessControl accessControl) {
        this.tenantIsolation = tenantIsolation;
        this.accessControl = accessControl;
    }

    /** 生成租户隔离的存储 key */
    public String resolveKey(MemoryId id, String tenantId) {
        String rawKey = id.id();
        return tenantIsolation.isolateKey(tenantId, rawKey);
    }

    /** 生成带作用域的隔离 key */
    public String resolveScopedKey(MemoryId id, MemoryScope scope, String scopeId, String tenantId) {
        String scopedKey = scope.isolate(scopeId, id.id());
        return tenantIsolation.isolateKey(tenantId, scopedKey);
    }

    /** 检查读权限（先租户隔离，后 Agent 授权） */
    public boolean authorizeRead(MemoryId id, String agentId, String tenantId) {
        // 代理到 AccessControl（内部应调用 SharedMemoryManager）
        return accessControl.checkReadAccess(id, agentId);
    }

    /** 检查写权限 */
    public boolean authorizeWrite(MemoryId id, String agentId, String tenantId) {
        return accessControl.checkWriteAccess(id, agentId);
    }

    /** 检查管理权限 */
    public boolean authorizeAdmin(String agentId) {
        return accessControl.checkAdminAccess(agentId);
    }

    public TenantIsolation tenantIsolation() {
        return tenantIsolation;
    }

    public AccessControl accessControl() {
        return accessControl;
    }
}
