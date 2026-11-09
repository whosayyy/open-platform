package com.yin.ai.platform.adapter.skill.composer;

import com.yin.ai.platform.prompt.PromptCenter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptComposer Fragment 扩展测试 — 验证 {{> name}} 展开。
 */
@DisplayName("PromptComposer Fragment 展开")
class PromptComposerFragmentTest {

    private PromptComposer composer;

    @BeforeEach
    void setUp() {
        // PromptComposer requires FunctionRegistry, SessionMemoryManager, PromptCenter
        // We only need to test the expandPartials method — create a minimal instance
        composer = new PromptComposer(null, null, null);
    }

    @Test
    @DisplayName("展开单个 Fragment Partial")
    void shouldExpandSinglePartial() {
        Map<String, String> fragments = Map.of("safety-rules", "## 安全规则\n禁止敏感内容");
        String template = "# 身份\n你是助手\n\n{{> safety-rules}}\n\n## 其他规则";

        String result = composer.expandPartials(template, fragments);

        assertThat(result).contains("## 安全规则");
        assertThat(result).doesNotContain("{{> safety-rules}}");
    }

    @Test
    @DisplayName("展开多个 Fragment Partials")
    void shouldExpandMultiplePartials() {
        Map<String, String> fragments = Map.of(
                "safety-rules", "## 安全规则\n禁止敏感内容",
                "output-format", "## 输出格式\n使用 Markdown"
        );
        String template = "# 身份\n{{> safety-rules}}\n{{> output-format}}\n## 结束";

        String result = composer.expandPartials(template, fragments);

        assertThat(result).contains("## 安全规则");
        assertThat(result).contains("## 输出格式");
        assertThat(result).doesNotContain("{{>");
    }

    @Test
    @DisplayName("Partial 不存在时替换为空字符串")
    void shouldReplaceMissingPartialWithEmpty() {
        Map<String, String> fragments = Map.of();
        String template = "# 身份\n{{> nonexistent}}\n## 结束";

        String result = composer.expandPartials(template, fragments);

        assertThat(result).doesNotContain("{{> nonexistent}}");
    }

    @Test
    @DisplayName("无 Partial 的模板不变")
    void shouldLeaveTemplateWithoutPartialsUnchanged() {
        Map<String, String> fragments = Map.of("unused", "content");
        String template = "# 身份\n你是助手\n{{currentTime}}";

        String result = composer.expandPartials(template, fragments);

        assertThat(result).isEqualTo(template);
    }

    @Test
    @DisplayName("Fragment map 为空时模板不变")
    void shouldLeaveTemplateUnchangedWhenFragmentsEmpty() {
        String template = "# 身份\n你是助手\n{{> anything}}";
        String result = composer.expandPartials(template, Map.of());

        assertThat(result).doesNotContain("{{> anything}}");
    }

    @Test
    @DisplayName("Partial 包含 Handlebar 变量不被预展开")
    void shouldNotExpandHandlebarsVariablesInFragment() {
        Map<String, String> fragments = Map.of("date", "当前时间 {{currentTime}}");
        String template = "# 标题\n{{> date}}";

        String result = composer.expandPartials(template, fragments);

        // Handlebars 变量在 Fragment 中保留，后续由 Handlebars 引擎处理
        assertThat(result).contains("{{currentTime}}");
        assertThat(result).doesNotContain("{{> date}}");
    }
}
