package com.yin.ai.platform.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yin.ai.platform.memory.config.MemoryProperties;
import com.yin.ai.platform.memory.consolidation.SessionConsolidationSchedule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 会话记忆管理器（Redis 存储）
 * 使用 RList&lt;String&gt; + Jackson JSON 手动序列化，避免 Redisson codec 的类型标识问题。
 * TTL 30 分钟无活动自动清除，用户退出页面时前端应调用 clearSession() 主动清除。
 */
@Slf4j
@Component
public class SessionMemoryManager {

    private static final String SESSION_KEY_PREFIX = "ai:session:v:";
    private static final String SESSION_OWNER_KEY_PREFIX = "ai:session:owner:";
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(30);

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final Duration sessionTtl;
    private final SessionConsolidationSchedule consolidationSchedule;

    public SessionMemoryManager(RedissonClient redissonClient) {
        this(redissonClient, DEFAULT_SESSION_TTL, null);
    }

    @Autowired
    public SessionMemoryManager(RedissonClient redissonClient, MemoryProperties properties,
                                ObjectProvider<SessionConsolidationSchedule> scheduleProvider) {
        this(redissonClient, properties.effectiveSessionTtl(), scheduleProvider.getIfAvailable());
    }

    public SessionMemoryManager(RedissonClient redissonClient, Duration sessionTtl) {
        this(redissonClient, sessionTtl, null);
    }

    public SessionMemoryManager(RedissonClient redissonClient, Duration sessionTtl,
                                SessionConsolidationSchedule consolidationSchedule) {
        this.redissonClient = redissonClient;
        this.objectMapper = createDefaultObjectMapper();
        this.sessionTtl = sessionTtl != null ? sessionTtl : DEFAULT_SESSION_TTL;
        this.consolidationSchedule = consolidationSchedule;
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    @PostConstruct
    void verifyRedisConnection() {
        try {
            String testKey = SESSION_KEY_PREFIX + "health-check";
            RList<String> list = redissonClient.getList(testKey);
            list.add("test");
            list.delete();
            log.info("[MEMORY] Redis 连接验证成功");
        } catch (Exception e) {
            log.error("[MEMORY] Redis 连接验证失败: {}", e.getMessage(), e);
        }
    }

    public void addMessage(String sessionId, String role, String content) {
        addMessage(sessionId, new ChatMessage(UUID.randomUUID().toString(), role,
                content, Instant.now(), Map.of()));
    }

    /** Stores the complete cell envelope while keeping the legacy Redis key. */
    public void addMessage(String sessionId, ChatMessage message) {
        String role = message != null ? message.role() : null;
        String content = message != null ? message.content() : null;
        if (sessionId == null || role == null || content == null) {
            log.warn("[MEMORY] addMessage skipped: sessionId={} role={} hasContent={}",
                    sessionId, role, content != null);
            return;
        }
        try {
            RList<String> list = getSessionList(sessionId);
            String json = objectMapper.writeValueAsString(message);
            list.add(json);
            list.expire(sessionTtl.toMillis(), TimeUnit.MILLISECONDS);
            if (consolidationSchedule != null) {
                Duration lead = sessionTtl.compareTo(Duration.ofMinutes(5)) > 0
                        ? Duration.ofMinutes(5) : Duration.ZERO;
                consolidationSchedule.schedule(sessionId, Instant.now().plus(sessionTtl).minus(lead));
            }
            log.info("[MEMORY] addMessage OK: sessionId={} role={} contentLen={}",
                    sessionId, role, content.length());
        } catch (Exception e) {
            log.error("[MEMORY] addMessage FAILED: sessionId={} role={}", sessionId, role, e);
        }
    }

    public List<ChatMessage> getHistory(String sessionId) {
        if (sessionId == null) return List.of();
        try {
            RList<String> list = getSessionList(sessionId);
            list.expire(sessionTtl.toMillis(), TimeUnit.MILLISECONDS);
            List<String> raw = list.readAll();
            List<ChatMessage> messages = new ArrayList<>();
            for (String json : raw) {
                try {
                    messages.add(objectMapper.readValue(json, ChatMessage.class));
                } catch (JsonProcessingException e) {
                    log.warn("[MEMORY] 跳过损坏的消息: {}", json, e);
                }
            }
            return messages;
        } catch (Exception e) {
            log.error("[MEMORY] getHistory FAILED: sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /**
     * 获取带 Token 预算的对话历史。
     * 当历史消息的 Token 总数超过 maxTokens 时，通过滑动窗口丢弃最早的对话轮次。
     *
     * @param sessionId 会话 ID
     * @param maxTokens Token 预算上限
     * @return 截断后的消息列表（按时间顺序）
     */
    public List<ChatMessage> getHistoryWithBudget(String sessionId, int maxTokens) {
        List<ChatMessage> history = getHistory(sessionId);
        if (history.isEmpty() || maxTokens <= 0) {
            return history;
        }
        return SlidingWindowTruncator.truncate(history, maxTokens);
    }

    public String buildConversationContext(String sessionId) {
        List<ChatMessage> history = getHistory(sessionId);
        if (history.isEmpty()) return "";
        return history.stream()
                .map(msg -> {
                    String roleLabel = "user".equals(msg.role()) ? "用户" : "助手";
                    return roleLabel + ": " + msg.content();
                })
                .collect(Collectors.joining("\n", "\n\n[对话历史]\n", ""));
    }

    public void setSessionOwner(String sessionId, Long userId) {
        if (sessionId == null || userId == null) return;
        redissonClient.getBucket(SESSION_OWNER_KEY_PREFIX + sessionId)
                .set(userId.toString(), sessionTtl.toMillis(), TimeUnit.MILLISECONDS);
    }

    public String getSessionOwner(String sessionId) {
        if (sessionId == null) return null;
        try {
            Object owner = redissonClient.getBucket(SESSION_OWNER_KEY_PREFIX + sessionId).get();
            return owner != null ? owner.toString() : null;
        } catch (Exception e) {
            log.warn("获取会话归属用户失败: sessionId={}", sessionId, e);
            return null;
        }
    }

    public void clearSession(String sessionId) {
        if (sessionId == null) return;
        redissonClient.getKeys().delete(SESSION_KEY_PREFIX + sessionId);
        redissonClient.getKeys().delete(SESSION_OWNER_KEY_PREFIX + sessionId);
        log.debug("会话记忆已清除: {}", sessionId);
    }

    private RList<String> getSessionList(String sessionId) {
        // 显式传入 StringCodec.INSTANCE，绕过 Redisson 默认 codec 的类型元数据前缀
        return redissonClient.getList(SESSION_KEY_PREFIX + sessionId, StringCodec.INSTANCE);
    }

    /**
     * New envelope. Jackson maps old {role,content} values with null optional fields,
     * while the two-argument constructor keeps all existing Java callers compatible.
     */
    public record ChatMessage(String cellId, String role, String content,
                              Instant timestamp, Map<String, Object> attributes) {
        public ChatMessage {
            attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        }

        public ChatMessage(String role, String content) {
            this(null, role, content, null, Map.of());
        }
    }

    /** Marks an intentional session end; consolidation owns any later source deletion. */
    public void endSession(String sessionId) {
        if (sessionId != null && consolidationSchedule != null) {
            consolidationSchedule.scheduleNow(sessionId);
        }
    }
}
