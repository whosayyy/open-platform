package com.yin.ai.platform.runtime.postprocess;

import com.yin.ai.platform.runtime.SkillExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ResponsePostProcessorChain")
class ResponsePostProcessorChainTest {

    private ResponsePostProcessorChain chain;
    private final List<String> executionOrder = new ArrayList<>();
    private final SkillExecutionContext ctx = SkillExecutionContext.builder().build();

    @BeforeEach
    void setUp() {
        executionOrder.clear();

        ResponsePostProcessor proc1 = new ResponsePostProcessor() {
            public String process(String r, SkillExecutionContext c) { executionOrder.add("p1"); return r + "[A]"; }
            public int order() { return 20; }
        };
        ResponsePostProcessor proc2 = new ResponsePostProcessor() {
            public String process(String r, SkillExecutionContext c) { executionOrder.add("p2"); return r + "[B]"; }
            public int order() { return 10; }
        };

        chain = new ResponsePostProcessorChain(List.of(proc1, proc2));
    }

    @Test
    @DisplayName("按 order 升序执行处理器")
    void shouldExecuteInOrder() {
        String result = chain.process("hello", ctx);
        assertThat(executionOrder).containsExactly("p2", "p1");
        assertThat(result).isEqualTo("hello[B][A]");
    }

    @Test
    @DisplayName("空处理器列表时返回原始内容")
    void shouldReturnRawWhenEmpty() {
        ResponsePostProcessorChain empty = new ResponsePostProcessorChain(Collections.emptyList());
        assertThat(empty.process("test", ctx)).isEqualTo("test");
        assertThat(empty.size()).isZero();
    }

    @Test
    @DisplayName("null 处理器列表时返回原始内容")
    void shouldReturnRawWhenNullList() {
        ResponsePostProcessorChain empty = new ResponsePostProcessorChain(null);
        assertThat(empty.process("test", ctx)).isEqualTo("test");
    }

    @Test
    @DisplayName("单个处理器失败时降级，不影响其他处理器")
    void shouldDegradeWhenProcessorFails() {
        ResponsePostProcessor failing = new ResponsePostProcessor() {
            public String process(String r, SkillExecutionContext c) { throw new RuntimeException("fail"); }
            public int order() { return 5; }
        };
        ResponsePostProcessor normal = new ResponsePostProcessor() {
            public String process(String r, SkillExecutionContext c) { return r + "[OK]"; }
            public int order() { return 10; }
        };

        ResponsePostProcessorChain failChain = new ResponsePostProcessorChain(List.of(failing, normal));
        String result = failChain.process("hello", ctx);
        assertThat(result).isEqualTo("hello[OK]");
    }

    @Test
    @DisplayName("null 和 blank 输入直接返回")
    void shouldReturnNullAndBlankDirectly() {
        assertThat(chain.process(null, ctx)).isNull();
        assertThat(chain.process("", ctx)).isEmpty();
    }
}
