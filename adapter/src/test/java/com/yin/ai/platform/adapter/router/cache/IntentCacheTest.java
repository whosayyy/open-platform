package com.yin.ai.platform.adapter.router.cache;

import com.yin.ai.platform.core.router.IntentMatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IntentCache 单元测试 — 使用短 TTL 验证缓存行为。
 */
@DisplayName("IntentCache")
class IntentCacheTest {

    private IntentCache cache;

    @BeforeEach
    void setUp() {
        cache = new IntentCache(new IntentCacheOptions(true, 10, 60));
    }

    @Test
    @DisplayName("禁用时 get 永远返回 empty")
    void shouldReturnEmptyWhenDisabled() {
        IntentCache disabled = new IntentCache(new IntentCacheOptions(false, 10, 60));

        IntentMatchResult result = IntentMatchResult.builder()
                .intent("chat").confidence(0.8).matchedBy("keyword").build();
        disabled.put("query", result);
        assertThat(disabled.get("query")).isEmpty();
    }

    @Test
    @DisplayName("写入 → 命中")
    void shouldCacheAndRetrieve() {
        IntentMatchResult result = IntentMatchResult.builder()
                .intent("search").confidence(0.9).matchedBy("keyword").build();

        cache.put("搜索科技新闻", result);
        Optional<IntentMatchResult> hit = cache.get("搜索科技新闻");

        assertThat(hit).isPresent();
        assertThat(hit.get().getIntent()).isEqualTo("search");
        assertThat(hit.get().getConfidence()).isEqualTo(0.9);
        assertThat(hit.get().getMatchedBy()).isEqualTo("keyword");
    }

    @Test
    @DisplayName("未写入的 key → miss")
    void shouldMissForUnknownKey() {
        assertThat(cache.get("不存在的查询")).isEmpty();
    }

    @Test
    @DisplayName("DefaultMatcher 结果不缓存")
    void shouldNotCacheDefaultMatcherResults() {
        IntentMatchResult fallback = IntentMatchResult.builder()
                .intent("chat").confidence(0.3).matchedBy("default").build();

        cache.put("随机问题", fallback);
        assertThat(cache.get("随机问题")).isEmpty();
    }

    @Test
    @DisplayName("null 查询不缓存")
    void shouldNotCacheNullQuery() {
        IntentMatchResult result = IntentMatchResult.builder()
                .intent("summary").confidence(0.8).matchedBy("keyword").build();
        cache.put(null, result);
        cache.put("", result);
        assertThat(cache.get(null)).isEmpty();
        assertThat(cache.get("")).isEmpty();
    }

    @Test
    @DisplayName("invalidateAll 清空所有缓存")
    void shouldInvalidateAll() {
        IntentMatchResult result = IntentMatchResult.builder()
                .intent("search").confidence(0.9).matchedBy("keyword").build();
        cache.put("搜索", result);
        assertThat(cache.get("搜索")).isPresent();

        cache.invalidateAll();
        assertThat(cache.get("搜索")).isEmpty();
    }

    @Test
    @DisplayName("hitCount / missCount 正确统计")
    void shouldTrackHitAndMissCounts() {
        IntentMatchResult result = IntentMatchResult.builder()
                .intent("chat").confidence(0.8).matchedBy("regex").build();
        cache.put("hello", result);

        cache.get("hello");
        cache.get("hello");
        cache.get("missing");

        assertThat(cache.hitCount()).isEqualTo(2);
        assertThat(cache.missCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("estimatedSize 反映缓存条目数")
    void shouldTrackEstimatedSize() {
        assertThat(cache.estimatedSize()).isZero();

        IntentMatchResult result = IntentMatchResult.builder()
                .intent("search").confidence(0.9).matchedBy("keyword").build();
        cache.put("key1", result);
        cache.put("key2", result);

        assertThat(cache.estimatedSize()).isEqualTo(2);
    }
}
