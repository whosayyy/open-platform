package com.yin.ai.platform.quickstart;

import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEvent;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.model.ModelRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 产品门面的规格测试 —— 这份测试定义"业务开发者只需要关注业务本身"到底长什么样。
 * <p>
 * 这是 open-platform 作为「产品」而非「库」的验收条件：
 * <ul>
 *   <li>业务代码只出现 2 个平台类型：{@link Agent} 和 {@link Result}</li>
 *   <li>不需要手写 ExecutionContext / traceId</li>
 *   <li>不需要手动实现 ModelClient（这里用 stub 模拟已配置好的模型）</li>
 *   <li>不需要手动注册 ToolRegistry</li>
 *   <li>不需要理解 DIRECT / REACT / PLAN_EXECUTE 等编排模式</li>
 * </ul>
 * 任何一条被打破，这个产品对业务开发者就不再成立。
 * <p>
 * 这里用确定性的 stub 模型而不是真实 API：门面的契约必须能在 CI 上验证。
 */
@DisplayName("QuickStart 门面规格")
class QuickStartTest {

    private static final String ARTICLE = """
            某地一位市民在社交平台发布消息，称当地一家超市即将停业，
            引发大量居民连夜排队抢购。经核实，该消息为不实信息。
            """;

    /** 记录最后一次请求，便于断言门面确实把 prompt 和输入传下去了。 */
    private final AtomicReference<ModelRequest> lastRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ModelClients.reset();
        ModelClients.register(new StubModelClient(lastRequest));
    }

    @Test
    @DisplayName("三行代码跑通一个业务 Agent")
    void businessDeveloperRunsAgentInThreeLines() {
        Agent agent = Agent.of("内容审核")
                .prompt("判断给定内容是否违规，只回答 PASS 或 REJECT，并给出一句理由。")
                .build();

        Result result = agent.run(ARTICLE);

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).isNotBlank();
        assertThat(result.error()).isNull();
    }

    @Test
    @DisplayName("prompt 与输入被正确传到模型，业务提示词进入 system 角色")
    void promptAndInputReachTheModel() {
        String prompt = "判断给定内容是否违规。";
        Agent agent = Agent.of("内容审核").prompt(prompt).build();

        agent.run(ARTICLE);

        ModelRequest request = lastRequest.get();
        assertThat(request).isNotNull();
        List<ModelMessage> messages = request.messages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(ModelRole.SYSTEM);
        assertThat(messages.get(0).content()).isEqualTo(prompt);
        assertThat(messages.get(1).role()).isEqualTo(ModelRole.USER);
        assertThat(messages.get(1).content()).isEqualTo(ARTICLE);
    }

    @Test
    @DisplayName("流式输出同样只需两个平台类型")
    void streamingUsesSameFacade() {
        Agent agent = Agent.of("内容审核")
                .prompt("判断给定内容是否违规。")
                .build();

        String text = agent.stream(ARTICLE)
                .map(Result::text)
                .collectList()
                .block()
                .stream()
                .reduce("", String::concat);

        assertThat(text).isNotBlank().contains("违规");
    }

    @Test
    @DisplayName("空输入返回可读错误，而不是抛平台内部异常")
    void blankInputFailsReadably() {
        Agent agent = Agent.of("内容审核")
                .prompt("判断给定内容是否违规。")
                .build();

        Result result = agent.run("   ");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isNotBlank().contains("input");
    }

    @Test
    @DisplayName("模型失败时返回 Result 而不是抛异常")
    void modelFailureIsReturnedAsResult() {
        ModelClients.reset();
        ModelClients.register(new FailingModelClient());
        Agent agent = Agent.of("内容审核").prompt("判断给定内容是否违规。").build();

        Result result = agent.run(ARTICLE);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("provider down");
    }

    @Test
    @DisplayName("同一个 Agent 可以被复用，无需重新构建")
    void agentIsReusable() {
        Agent agent = Agent.of("内容审核")
                .prompt("判断给定内容是否违规。")
                .build();

        Result first = agent.run(ARTICLE);
        Result second = agent.run("今天天气不错。");

        assertThat(first.ok()).isTrue();
        assertThat(second.ok()).isTrue();
    }

    @Test
    @DisplayName("缺少 prompt 时构建失败并给出可执行提示")
    void missingPromptFailsAtBuildTime() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                Agent.of("未命名").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".prompt(");
    }

    @Test
    @DisplayName("没有可用模型时给出可执行的修复指引")
    void missingModelGivesActionableError() {
        ModelClients.reset();
        Agent agent = Agent.of("内容审核").prompt("判断给定内容是否违规。").build();

        Result result = agent.run(ARTICLE);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("ModelClients.register");
    }

    // ---------------- 测试替身 ----------------

    /** 同步返回固定文本，流式按字符切片，模拟真实流式行为。 */
    private static final class StubModelClient implements ModelClient {

        private static final String RESPONSE = "REJECT 该内容为不实信息，属于违规内容。";

        private final AtomicReference<ModelRequest> recorder;

        private StubModelClient(AtomicReference<ModelRequest> recorder) {
            this.recorder = recorder;
        }

        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            recorder.set(request);
            return new ModelResponse(RESPONSE, "stop", null, null);
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            recorder.set(request);
            // 逐片推送增量，验证门面把它映射成增量 Result
            for (int i = 0; i < RESPONSE.length(); i += 6) {
                listener.onEvent(new ModelEvent("text",
                        RESPONSE.substring(i, Math.min(RESPONSE.length(), i + 6)), null));
            }
            return new StubModelCall();
        }
    }

    private static final class StubModelCall implements ModelCall {

        @Override
        public java.util.concurrent.CompletionStage<ModelResponse> completion() {
            return CompletableFuture.completedFuture(
                    new ModelResponse("", "stop", null, null));
        }

        @Override
        public boolean cancel() {
            return true;
        }
    }

    private static final class FailingModelClient implements ModelClient {

        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            throw new IllegalStateException("provider down");
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            throw new IllegalStateException("provider down");
        }
    }
}
