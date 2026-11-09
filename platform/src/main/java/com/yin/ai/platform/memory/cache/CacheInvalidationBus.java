package com.yin.ai.platform.memory.cache;

import java.util.List;

/**
 * 缓存失效总线 SPI — 在多实例部署时广播 L1 缓存失效消息。
 * <p>
 * 问题场景：
 * <pre>
 *   实例A: 写 cell → L2 Redis → 回填 L1(A)
 *   实例B: 读同一 cell → L1(B) miss → L2 Redis (OK)
 *   实例C: 读同一 cell → L1(C) STALE  ← 实例C 不知道 A 写过了
 * </pre>
 * <p>
 * 解决方案：
 * <ol>
 *   <li>写入成功后发布 invalidation 消息（key = memoryId）</li>
 *   <li>所有实例订阅该消息，收到后清空对应 L1 条目</li>
 *   <li>消息丢失时 L1 TTL 自动过期兜底（最终一致性）</li>
 * </ol>
 * <p>
 * 实现：
 * <ul>
 *   <li>Redis Pub/Sub（推荐）：利用已有 RedissonClient，零额外依赖</li>
 *   <li>MQ（RabbitMQ/Kafka）：已有基础设施，可靠性更高</li>
 *   <li>Noop（开发/单实例）：不做广播，仅依赖 L1 TTL</li>
 * </ul>
 */
public interface CacheInvalidationBus {

    /**
     * 发布缓存失效消息。
     *
     * @param key 需要失效的缓存 key（通常是 MemoryId.id() 或完整的复合 key）
     */
    void publish(String key);

    /**
     * 批量发布。
     */
    default void publishAll(List<String> keys) {
        for (String key : keys) {
            publish(key);
        }
    }

    /**
     * 订阅失效消息，收到后回调 listener。
     *
     * @param listener 收到失效消息时的处理逻辑
     */
    void subscribe(CacheInvalidationListener listener);

    /**
     * 关闭总线，释放资源。
     */
    void close();

    /**
     * 缓存失效监听器。
     */
    @FunctionalInterface
    interface CacheInvalidationListener {
        void onInvalidate(String key);
    }
}
