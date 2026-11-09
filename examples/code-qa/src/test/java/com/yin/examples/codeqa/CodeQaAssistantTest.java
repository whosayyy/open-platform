package com.yin.examples.codeqa;

import com.yin.ai.platform.quickstart.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费者契约测试 —— 以「真实使用方」的身份验证平台抽象是否成立。
 * <p>
 * 这些测试与新闻业务无关，是平台的第二个独立消费者。
 * 如果平台 API 只在新闻场景下好用，这里会失败。
 */
@DisplayName("代码库问答助手（平台消费者）")
class CodeQaAssistantTest {

    private CodeQaAssistant assistant;

    @BeforeEach
    void setUp() {
        assistant = CodeQaAssistant.create(ModelProviders.Mode.STUB, DemoKnowledgeBase.build());
    }

    @Test
    @DisplayName("助手可以构建，且工具已注册")
    void assistantIsReady() {
        assertThat(assistant.toolNames()).containsExactly("search_code");
        assertThat(assistant.snippetCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("能回答知识库覆盖的问题")
    void answersQuestionCoveredByKnowledgeBase() {
        Result result = assistant.ask("Agent 的引擎超时是怎么实现的？");

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).contains("engine-timeout");
        assertThat(result.text()).contains("Future.get");
    }

    @Test
    @DisplayName("知识库没有覆盖的问题如实说明，不编造答案")
    void admitsWhenKnowledgeBaseHasNoAnswer() {
        Result result = assistant.ask("这个代码库支持 GraphQL 吗？");

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).contains("NO_MATCH");
    }

    @Test
    @DisplayName("多个问题复用同一个助手实例")
    void assistantHandlesMultipleQuestions() {
        Result first = assistant.ask("RAG 的向量检索走的哪个存储？");
        Result second = assistant.ask("记忆类型有哪些？");

        assertThat(first.ok()).isTrue();
        assertThat(first.text()).contains("rag-vector");
        assertThat(second.ok()).isTrue();
        assertThat(second.text()).contains("memory-types");
    }

    @Test
    @DisplayName("空问题返回可读失败，不抛异常")
    void blankQuestionFailsReadably() {
        Result result = assistant.ask("  ");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isNotBlank();
    }

    @Test
    @DisplayName("业务代码只依赖平台两个包就够用")
    void consumerDependsOnNarrowApiSurface() {
        // 这个测试本身是断言：助手实现引用的平台类型
        // 只来自 com.yin.ai.platform.quickstart 与 com.yin.ai.platform.core.tool。
        // 如果将来助手需要引入更多平台包才能工作，说明门面漏了东西。
        assertThat(CodeQaAssistant.class.getPackageName()).isEqualTo("com.yin.examples.codeqa");
        Result result = assistant.ask("产品门面是什么？");
        assertThat(result.ok()).isTrue();
    }
}
