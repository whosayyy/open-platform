package com.yin.ai.platform.memory.config;

import com.yin.ai.platform.core.memory.store.StoreType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;
import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "ai.memory")
public class MemoryProperties {

    private L1 l1 = new L1();
    private L2 l2 = new L2();
    private Store store = new Store();
    private Redis redis = new Redis();
    private Retention retention = new Retention();
    private Orchestration orchestration = new Orchestration();
    private Retrieval retrieval = new Retrieval();
    private Migration migration = new Migration();

    @Data
    public static class L1 {
        /** Caffeine 最大条目数 */
        private int maxSize = 10_000;
        /** Caffeine TTL，写入后过期 */
        private Duration ttl = Duration.ofMinutes(5);
        /** 是否记录缓存命中率统计 */
        private boolean recordStats = true;
    }

    @Data
    public static class L2 {
        /** Redis key 前缀 */
        private String keyPrefix = "ai:memory:";
        /** 默认 TTL。 */
        private Duration defaultTtl = Duration.ofHours(1);
        private boolean defaultTtlConfigured;
        /** 旧配置兼容；新配置 {@code default-ttl} 优先。 */
        @Deprecated
        private Long defaultTtlSeconds;
        /** 是否启用写穿透（写入 L2 后回填 L1） */
        private boolean writeThrough = true;
        /** 是否启用空值缓存防穿透 */
        private boolean cacheNullValues = true;

        public void setDefaultTtl(Duration defaultTtl) {
            this.defaultTtl = defaultTtl;
            this.defaultTtlConfigured = true;
        }
    }

    @Data
    public static class Store {
        /** 默认存储类型 */
        private StoreType defaultType = StoreType.IN_MEMORY;
        /** 是否启用 CAS 乐观锁写入 */
        private boolean casEnabled = false;
        /** CAS 最大重试次数 */
        private int casMaxRetries = 3;
    }

    @Data
    public static class Redis {
        /** 旧配置兼容；新配置 {@code ai.memory.retention.episodic-ttl} 优先。 */
        @Deprecated
        private Long ttlSeconds;
    }

    @Data
    public static class Retention {
        /** Agent 运行时状态。 */
        private Duration workingTtl = Duration.ofHours(1);
        /** 短会话对话。 */
        private Duration sessionTtl = Duration.ofMinutes(30);
        /** 近期经历；到期前应先巩固。 */
        private Duration episodicTtl = Duration.ofDays(7);
        private boolean episodicTtlConfigured;
        /** 恢复检查点。 */
        private Duration checkpointTtl = Duration.ofDays(30);

        public void setEpisodicTtl(Duration episodicTtl) {
            this.episodicTtl = episodicTtl;
            this.episodicTtlConfigured = true;
        }
    }

    @Data
    public static class Retrieval {
        /** 将 ES BM25、关键词和向量结果通过 RRF 融合。 */
        private boolean hybridEnabled = true;
        /** 新记忆写入时生成向量，并在文本查询时生成查询向量。 */
        private boolean embeddingEnabled = true;
        /** 防止 fallback 在单次请求中无限制扫描 Cell。 */
        private int maxLocalCandidates = 5_000;
        /** 缺少 confidence 元数据时的保守默认值。 */
        private double defaultConfidence = 0.85;
    }

    @Data
    public static class Orchestration {
        /** Enables policy-governed memory APIs. */
        private boolean enabled = true;
        /** Evaluates decisions without changing legacy write behavior. */
        private boolean shadowMode = true;
        /** Moves callers from the compatibility retrieval path to the orchestrator. */
        private boolean retrievalEnabled = false;
        /** Persists promoted Semantic facts to MySQL. */
        private boolean semanticWriteEnabled = false;
        /** Runs the transactional-outbox Elasticsearch projector. */
        private boolean projectionEnabled = false;
        /** Enables background/manual promotion from Episodes. */
        private boolean consolidationEnabled = false;
        /** Exposes the destructive forget pipeline. */
        private boolean forgetEnabled = false;
        /** Compatibility bridge until a real organization tenant is propagated. */
        private boolean userAsTenantFallback = true;
        private int defaultTokenBudget = 2_000;
    }

    @Data
    public static class Migration {
        /** 当前数据版本 */
        private int currentVersion = 1;
        /** 是否自动执行迁移 */
        private boolean autoMigrate = false;
    }

    /** Redis Working Store 使用的最终 TTL。 */
    public Duration effectiveWorkingTtl() {
        return requirePositive(retention.workingTtl, "retention.working-ttl");
    }

    /** Redis Session Store 使用的最终 TTL。 */
    public Duration effectiveSessionTtl() {
        return requirePositive(retention.sessionTtl, "retention.session-ttl");
    }

    /** 新 Duration 配置优先；仅当其为空时才读取旧秒数配置。 */
    public Duration effectiveEpisodicTtl() {
        if (retention.episodicTtlConfigured) {
            return requirePositive(retention.episodicTtl, "retention.episodic-ttl");
        }
        if (redis.ttlSeconds != null) {
            return requirePositive(Duration.ofSeconds(redis.ttlSeconds), "redis.ttl-seconds");
        }
        return requirePositive(retention.episodicTtl, "retention.episodic-ttl");
    }

    public Duration effectiveL2Ttl() {
        if (l2.defaultTtlConfigured) {
            return requirePositive(l2.defaultTtl, "l2.default-ttl");
        }
        if (l2.defaultTtlSeconds != null) {
            return requirePositive(Duration.ofSeconds(l2.defaultTtlSeconds), "l2.default-ttl-seconds");
        }
        return requirePositive(l2.defaultTtl, "l2.default-ttl");
    }

    @PostConstruct
    void validate() {
        Duration l1Ttl = requirePositive(l1.ttl, "l1.ttl");
        Duration l2Ttl = effectiveL2Ttl();
        if (l1Ttl.compareTo(l2Ttl) > 0) {
            throw new IllegalStateException("ai.memory.l1.ttl must not exceed ai.memory.l2.default-ttl");
        }
        effectiveWorkingTtl();
        effectiveSessionTtl();
        effectiveEpisodicTtl();
        requirePositive(retention.checkpointTtl, "retention.checkpoint-ttl");
    }

    private static Duration requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("ai.memory." + property + " must be positive");
        }
        return value;
    }
}
