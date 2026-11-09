package com.yin.ai.platform.core.memory.security;

/**
 * Memory 作用域层级 — 定义 MemoryId 的可见性和隔离边界。
 * <p>
 * 层级关系（子作用域可读取祖先数据，写入仅限当前作用域）：
 * <pre>
 * GLOBAL → TENANT → USER → SESSION → TASK → WORKFLOW → AGENT → STEP
 * </pre>
 * <p>
 * 使用示例：
 * <pre>
 *   // 租户级别隔离 key
 *   String isolated = MemoryScope.TENANT.isolate("tenant-a", "agent-session-1");
 *   // → "tenant:tenant-a:agent-session-1"
 *
 *   // 检查作用域层级
 *   MemoryScope.TASK.isChildOf(MemoryScope.SESSION); // true
 * </pre>
 */
public enum MemoryScope {
    /** 全局 — 无隔离 */
    GLOBAL(0),
    /** 租户 — tenantId 前缀 */
    TENANT(1),
    /** 用户 — userId 前缀 */
    USER(2),
    /** 会话 — sessionId 前缀 */
    SESSION(3),
    /** 任务 — taskId 前缀 */
    TASK(4),
    /** 工作流 — workflowInstanceId 前缀 */
    WORKFLOW(4),
    /** Agent — agentId 前缀 */
    AGENT(5),
    /** 步骤 — stepId 前缀（最细粒度） */
    STEP(6);

    private final int level;

    MemoryScope(int level) {
        this.level = level;
    }

    /** 当前作用域是否是 target 的祖先（或同层级） */
    public boolean isAncestorOf(MemoryScope target) {
        return this.level <= target.level;
    }

    /** 当前作用域是否是 target 的子作用域 */
    public boolean isChildOf(MemoryScope target) {
        return this.level > target.level;
    }

    /** 根据作用域生成隔离 key */
    public String isolate(String scopeId, String rawKey) {
        return prefix() + scopeId + ":" + rawKey;
    }

    private String prefix() {
        return name().toLowerCase() + ":";
    }

    /** 从 key 中提取作用域前缀 */
    public static String extractScopeId(String isolatedKey, MemoryScope scope) {
        String prefix = scope.prefix();
        if (!isolatedKey.startsWith(prefix)) {
            return "";
        }
        String rest = isolatedKey.substring(prefix.length());
        int nextColon = rest.indexOf(':');
        return nextColon > 0 ? rest.substring(0, nextColon) : rest;
    }
}
