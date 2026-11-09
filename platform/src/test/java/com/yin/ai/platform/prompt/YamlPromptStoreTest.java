package com.yin.ai.platform.prompt;

import com.yin.ai.platform.prompt.model.PromptFragment;
import com.yin.ai.platform.prompt.store.YamlPromptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YamlPromptStore 单元测试 — 验证 Fragment 加载、版本过滤、缺失处理。
 */
@DisplayName("YamlPromptStore")
class YamlPromptStoreTest {

    private YamlPromptStore store;

    @BeforeEach
    void setUp() {
        store = new YamlPromptStore();
    }

    @Test
    @DisplayName("加载存在的 Fragment：safety-rules")
    void shouldLoadSafetyRulesFragment() {
        Optional<PromptFragment> fragment = store.loadLatestFragment("safety-rules");
        assertThat(fragment).isPresent();
        assertThat(fragment.get().getName()).isEqualTo("safety-rules");
        assertThat(fragment.get().getFragment()).contains("内容安全规则");
        assertThat(fragment.get().getFragment()).contains("绝对禁止");
    }

    @Test
    @DisplayName("加载存在的 Fragment：output-format")
    void shouldLoadOutputFormatFragment() {
        Optional<PromptFragment> fragment = store.loadLatestFragment("output-format");
        assertThat(fragment).isPresent();
        assertThat(fragment.get().getName()).isEqualTo("output-format");
        assertThat(fragment.get().getFragment()).contains("输出格式要求");
    }

    @Test
    @DisplayName("加载存在的 Fragment：date-context")
    void shouldLoadDateContextFragment() {
        Optional<PromptFragment> fragment = store.loadLatestFragment("date-context");
        assertThat(fragment).isPresent();
        assertThat(fragment.get().getName()).isEqualTo("date-context");
        assertThat(fragment.get().getDescription()).contains("日期参数计算规则");
    }

    @Test
    @DisplayName("不存在的 Fragment 返回 empty")
    void shouldReturnEmptyForUnknownFragment() {
        assertThat(store.loadLatestFragment("nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("指定版本加载 fragment — latest 等同于最新版")
    void shouldLoadFragmentWithVersion() {
        Optional<PromptFragment> byName = store.loadFragment("safety-rules", "latest");
        Optional<PromptFragment> latest = store.loadLatestFragment("safety-rules");
        assertThat(byName).isPresent();
        assertThat(latest).isPresent();
        assertThat(byName.get().getVersion()).isEqualTo(latest.get().getVersion());
    }

    @Test
    @DisplayName("指定版本加载 fragment — 精确版本匹配")
    void shouldLoadFragmentWithExactVersion() {
        Optional<PromptFragment> fragment = store.loadFragment("safety-rules", "2.0.0");
        assertThat(fragment).isPresent();
        assertThat(fragment.get().getName()).isEqualTo("safety-rules");
    }

    @Test
    @DisplayName("Fragment 包含 changelog")
    void shouldHaveChangelog() {
        Optional<PromptFragment> fragment = store.loadLatestFragment("safety-rules");
        assertThat(fragment).isPresent();
        assertThat(fragment.get().getChangelog()).isNotEmpty();
    }

    @Test
    @DisplayName("refresh 清空缓存")
    void shouldRefreshCache() {
        store.loadLatestFragment("safety-rules");
        store.refresh();
        // 刷新后仍能正常加载
        Optional<PromptFragment> fragment = store.loadLatestFragment("safety-rules");
        assertThat(fragment).isPresent();
    }
}
