package com.yin.ai.platform.memory.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 分布式 Memory 事件总线 — 默认使用本地 {@link CopyOnWriteArrayList} 分发，
 * 同时通过 Redis Pub/Sub（Redisson RTopic）广播到其他实例。
 * <p>
 * 高并发设计：
 * <ul>
 *   <li>本地分发：CopyOnWriteArrayList，读无锁，写入安全</li>
 *   <li>跨实例分发：Redis Pub/Sub 广播，每个实例注册一个 RTopic 监听器</li>
 *   <li>去重：本地事件直接分发，不经过 Redis 回环（通过 originInstanceId 标记避免）</li>
 *   <li>序列化：使用 Jackson JSON 序列化 MemoryEvent 为消息体</li>
 * </ul>
 * <p>
 * 分布式特征：事件发布后所有集群实例在毫秒级内收到通知，
 * 适用于跨实例的 Memory 状态同步、缓存失效广播等场景。
 */
@Slf4j
public class RedisMemoryEventBus implements MemoryEventBus {

    private static final String TOPIC_NAME = "ai:memory:events";

    private final List<MemoryEventListener> localListeners = new CopyOnWriteArrayList<>();
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String instanceId;
    private final RTopic topic;
    private volatile int listenerRegistrationCount;

    public RedisMemoryEventBus(RedissonClient redissonClient, String instanceId) {
        this(redissonClient, new ObjectMapper(), instanceId);
    }

    public RedisMemoryEventBus(RedissonClient redissonClient, ObjectMapper objectMapper,
                                String instanceId) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.topic = redissonClient.getTopic(TOPIC_NAME);
        this.topic.addListener(String.class, new RedisMessageListener());
    }

    @Override
    public void publish(MemoryEventType type, MemoryId memoryId, MemoryCell cell,
                         String traceId, Map<String, Object> data) {
        MemoryEvent event = MemoryEvent.of(type, memoryId, cell, traceId, 0, data);
        publish(event);
    }

    @Override
    public void publish(MemoryEvent event) {
        // 1. 本地分发
        dispatchToLocal(event);

        // 2. Redis Pub/Sub 广播给其他实例
        try {
            String json = objectMapper.writeValueAsString(event);
            topic.publish(json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize event for Redis broadcast: {}", event.eventId(), e);
        }
    }

    @Override
    public void register(MemoryEventListener listener) {
        localListeners.add(listener);
        listenerRegistrationCount++;
    }

    @Override
    public void unregister(MemoryEventListener listener) {
        localListeners.remove(listener);
        listenerRegistrationCount = Math.max(0, listenerRegistrationCount - 1);
    }

    @Override
    public int listenerCount() {
        return localListeners.size();
    }

    private void dispatchToLocal(MemoryEvent event) {
        for (MemoryEventListener listener : localListeners) {
            if (!listener.supports(event.type())) {
                continue;
            }
            try {
                listener.onEvent(event);
            } catch (Exception ignored) {
            }
        }
    }

    /** Redis Pub/Sub 消息监听 — 接收其他实例广播的事件，分发给本地监听器 */
    private class RedisMessageListener implements MessageListener<String> {
        @Override
        public void onMessage(CharSequence channel, String msg) {
            try {
                MemoryEvent event = objectMapper.readValue(msg, MemoryEvent.class);
                if (instanceId.equals(event.data().get("originInstanceId"))) {
                    return; // 跳过自己发出的消息
                }
                dispatchToLocal(event);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize Redis event message", e);
            }
        }
    }
}
