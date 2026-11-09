package com.yin.ai.platform.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KnowledgeRegistry 单元测试。
 */
@DisplayName("KnowledgeRegistry")
class KnowledgeRegistryTest {

    @Test
    @DisplayName("空名称列表返回空")
    void shouldReturnEmptyForEmptyNames() {
        KnowledgeRegistry registry = new KnowledgeRegistry(List.of());
        assertThat(registry.retrieve(List.of(), "query")).isEmpty();
        assertThat(registry.retrieve(null, "query")).isEmpty();
    }

    @Test
    @DisplayName("空查询返回空")
    void shouldReturnEmptyForBlankQuery() {
        KnowledgeBase kb = new StubKnowledgeBase("stub", "result");
        KnowledgeRegistry registry = new KnowledgeRegistry(List.of(kb));
        assertThat(registry.retrieve(List.of("stub"), "")).isEmpty();
        assertThat(registry.retrieve(List.of("stub"), null)).isEmpty();
        assertThat(registry.retrieve(List.of("stub"), "   ")).isEmpty();
    }

    @Test
    @DisplayName("未知知识库跳过并拼接已知的结果")
    void shouldSkipUnknownAndConcatenateKnown() {
        KnowledgeBase kb1 = new StubKnowledgeBase("kb1", "内容1");
        KnowledgeBase kb2 = new StubKnowledgeBase("kb2", "内容2");
        KnowledgeRegistry registry = new KnowledgeRegistry(List.of(kb1, kb2));

        String result = registry.retrieve(List.of("kb1", "unknown", "kb2"), "query");
        assertThat(result).contains("内容1");
        assertThat(result).contains("内容2");
        assertThat(result).doesNotContain("unknown");
    }

    @Test
    @DisplayName("多个知识库结果用双换行拼接")
    void shouldConcatenateWithDoubleNewline() {
        KnowledgeBase kb1 = new StubKnowledgeBase("kb1", "AAA");
        KnowledgeBase kb2 = new StubKnowledgeBase("kb2", "BBB");
        KnowledgeRegistry registry = new KnowledgeRegistry(List.of(kb1, kb2));

        String result = registry.retrieve(List.of("kb1", "kb2"), "query");
        assertThat(result).isEqualTo("AAA\n\nBBB");
    }

    @Test
    @DisplayName("KnowledgeBase 异常时不影响其他知识库")
    void shouldIsolateExceptions() {
        KnowledgeBase ok = new StubKnowledgeBase("ok", "正常结果");
        KnowledgeBase fail = new KnowledgeBase() {
            @Override
            public String getName() { return "fail"; }
            @Override
            public String retrieve(String query) {
                throw new RuntimeException("boom");
            }
        };
        KnowledgeRegistry registry = new KnowledgeRegistry(List.of(ok, fail));

        String result = registry.retrieve(List.of("fail", "ok"), "query");
        assertThat(result).isEqualTo("正常结果");
    }

    @Test
    @DisplayName("重复名称覆盖并打印警告")
    void shouldOverwriteDuplicateName() {
        KnowledgeBase kb1 = new StubKnowledgeBase("dup", "first");
        KnowledgeBase kb2 = new StubKnowledgeBase("dup", "second");
        KnowledgeRegistry registry = new KnowledgeRegistry(List.of(kb1, kb2));

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.retrieve(List.of("dup"), "query")).isEqualTo("second");
    }

    /** 简单桩实现 — 固定返回预设内容。 */
    static class StubKnowledgeBase implements KnowledgeBase {
        private final String name;
        private final String result;

        StubKnowledgeBase(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String retrieve(String query) { return result; }
    }
}
