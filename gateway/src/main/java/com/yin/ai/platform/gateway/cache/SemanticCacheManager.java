package com.yin.ai.platform.gateway.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.gateway.GatewayProperties;
import com.yin.ai.platform.gateway.config.SemanticCacheConfig;
import com.yin.security.util.GetLoginUserUtil;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 语义缓存管理器 — 基于 Embedding 向量相似度的 LLM 响应缓存。
 * <p>
 * 存储委托 {@link SemanticCacheStore} SPI，保证多实例数据一致性：
 * <ul>
 *   <li>当前：{@link RedisSemanticCacheStore} — Redis RMap + Java cosine 相似度遍历</li>
 *   <li>未来：Elasticsearch dense_vector kNN 替换 cosine 遍历（实现 SemanticCacheStore 即可）</li>
 * </ul>
 * <p>
 * <b>缓存质量保障</b>：拒绝缓存过短响应（&lt;50 字符）、纯标点/emoji、或
 * 明显为系统模板问候语的响应，避免"你好→欢迎语"类条目被其他 query 误命中。
 */
@Slf4j
public class SemanticCacheManager {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 最小缓存响应长度 —— 短于该值的响应（如"好的！"、"CONTINUE"）不缓存 */
    private static final int MIN_RESPONSE_LENGTH = 50;

    /** 拒识短语 —— 包含任一短语的响应不应被缓存 */
    private static final Set<String> REJECTION_PATTERNS = Set.of(
            "抱歉", "对不起", "我无法", "我不能", "我只会", "我只能", "无法执行",
            "无法处理", "无法完成", "无法满足", "无法理解"
    );

    /** 拒识 emoji —— 正常业务响应不应包含这些 */
    private static final Set<String> REJECTION_EMOJI = Set.of(
            "😊", "🙏", "😅", "🤔", "😢", "💡"
    );

    private final SemanticCacheStore store;
    private final EmbeddingModel embeddingModel;
    private final SemanticCacheConfig config;

    public SemanticCacheManager(SemanticCacheStore store,
                                 EmbeddingModel embeddingModel,
                                 SemanticCacheConfig config) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.config = config;
    }

    /**
     * 搜索语义缓存，返回匹配的缓存内容或 null。
     * 从 Redis 读取当前租户条目并在 Java 侧计算 cosine 相似度。
     */
    public String search(String userInput) {
        if (!config.isEnabled() || userInput == null || userInput.isBlank()) {
            return null;
        }

        long start = System.currentTimeMillis();
        try {
            Embedding queryEmbedding = embeddingModel.embed(userInput).content();
            float[] queryVector = queryEmbedding.vector();

            String tenantPrefix = "semantic:" + resolveTenantId() + ":";
            Map<String, String> allEntries = store.readAll();
            if (allEntries.isEmpty()) {
                return null;
            }

            double bestScore = 0;
            String bestContent = null;
            int scanned = 0;

            for (Map.Entry<String, String> entry : allEntries.entrySet()) {
                if (!entry.getKey().startsWith(tenantPrefix)) {
                    continue;
                }
                try {
                    CacheEntry cacheEntry = objectMapper.readValue(
                            entry.getValue(), CacheEntry.class);
                    if (cacheEntry.getEmbedding() == null) {
                        continue;
                    }
                    if (!isCacheableResponse(cacheEntry.getContent())) {
                        continue;
                    }
                    double similarity = cosineSimilarity(
                            queryVector, cacheEntry.getEmbedding());
                    if (similarity > bestScore) {
                        bestScore = similarity;
                        bestContent = cacheEntry.getContent();
                    }
                    scanned++;
                } catch (JsonProcessingException e) {
                    log.debug("[SEMANTIC-CACHE] skip corrupt entry: {}", entry.getKey());
                }
            }

            long duration = System.currentTimeMillis() - start;
            if (bestScore >= config.getSimilarityThreshold() && bestContent != null) {
                log.info("[SEMANTIC-CACHE] HIT score={:.3f} entriesScanned={} durationMs={}",
                        bestScore, scanned, duration);
                return bestContent;
            }

            log.debug("[SEMANTIC-CACHE] MISS bestScore={:.3f} threshold={} entriesScanned={} durationMs={}",
                    bestScore, config.getSimilarityThreshold(), scanned, duration);
            return null;

        } catch (Exception e) {
            log.warn("[SEMANTIC-CACHE] search failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 存储 LLM 响应到语义缓存（直接写 Redis，所有实例共享）。
     * <p>
     * 跳过低质量响应（过短、纯问候语），避免污染缓存池。
     */
    public void store(String userInput, String responseContent, String model) {
        if (!config.isEnabled() || userInput == null || responseContent == null) {
            return;
        }
        if (!isCacheableResponse(responseContent)) {
            log.debug("[SEMANTIC-CACHE] skip storing trivial response (len={})", responseContent.length());
            return;
        }

        try {
            Embedding embedding = embeddingModel.embed(userInput).content();
            CacheEntry entry = new CacheEntry(embedding.vector(), responseContent, model);

            String key = "semantic:" + resolveTenantId() + ":" + UUID.randomUUID();
            store.put(key, objectMapper.writeValueAsString(entry));

            evictIfNeeded();

            log.debug("[SEMANTIC-CACHE] stored entry key={}", key);
        } catch (Exception e) {
            log.warn("[SEMANTIC-CACHE] store failed: {}", e.getMessage());
        }
    }

    /**
     * 判断响应内容是否值得缓存。
     * <p>
     * 过滤规则：
     * <ul>
     *   <li>长度 < 50 字符 → 过短（问候语、单字回复、CONTINUE/COMPLETE）</li>
     *   <li>包含拒识短语 → 可能是管道内部错误响应，不应缓存</li>
     *   <li>包含拒识 emoji → 同上</li>
     * </ul>
     */
    static boolean isCacheableResponse(String content) {
        if (content == null || content.length() < MIN_RESPONSE_LENGTH) {
            return false;
        }
        for (String pattern : REJECTION_PATTERNS) {
            if (content.contains(pattern)) {
                return false;
            }
        }
        for (String emoji : REJECTION_EMOJI) {
            if (content.contains(emoji)) {
                return false;
            }
        }
        return true;
    }

    private void evictIfNeeded() {
        int size = store.size();
        if (size <= config.getMaxEntries()) {
            return;
        }

        int toRemove = size - config.getMaxEntries();
        int removed = 0;
        for (var it = store.keys().iterator(); it.hasNext() && removed < toRemove; ) {
            String key = it.next();
            store.remove(key);
            removed++;
        }
        if (removed > 0) {
            log.info("[SEMANTIC-CACHE] evicted {} entries (size={} max={})",
                    removed, size, config.getMaxEntries());
        }
    }

    private static String resolveTenantId() {
        try {
            Long userId = GetLoginUserUtil.getUserId();
            return userId != null ? userId.toString() : "default";
        } catch (Exception e) {
            return "default";
        }
    }

    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
