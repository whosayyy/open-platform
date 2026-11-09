package com.yin.ai.platform.agent.integration;

import com.yin.ai.platform.function.FunctionDefinition;
import com.yin.ai.platform.function.FunctionInput;
import com.yin.ai.platform.function.FunctionResult;
import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.core.skill.SkillManifest;
import com.yin.ai.platform.runtime.SkillExecutionContext;
import com.yin.ai.platform.runtime.SkillResult;
import com.yin.ai.platform.runtime.SkillRuntime;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentSkillFunction")
class AgentSkillFunctionTest {

    private StubSkillLoader skillLoader;
    private StubSkillRuntime skillRuntime;
    private AgentSkillFunction fn;

    @BeforeEach
    void setUp() {
        skillLoader = new StubSkillLoader();
        skillRuntime = new StubSkillRuntime();
        fn = new AgentSkillFunction("summary", skillRuntime, skillLoader);
    }

    @Test
    @DisplayName("missing userInput returns failure")
    void missingUserInput() {
        FunctionResult result = fn.execute(FunctionInput.of(Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("userInput");
    }

    @Test
    @DisplayName("blank userInput returns failure")
    void blankUserInput() {
        FunctionResult result = fn.execute(FunctionInput.of(Map.of("userInput", "   ")));

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("successful skill execution returns data")
    void successfulExecution() {
        skillRuntime.setResult(SkillResult.ok("summary text here"));

        FunctionResult result = fn.execute(FunctionInput.of(Map.of("userInput", "summarize this")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("summary text here");
        assertThat(skillRuntime.lastSkillName).isEqualTo("summary");
        assertThat(skillRuntime.lastCtx.getUserInput()).isEqualTo("summarize this");
    }

    @Test
    @DisplayName("skill failure propagates error")
    void skillFailure() {
        RuntimeException ex = new RuntimeException("skill crashed");
        skillRuntime.setResult(SkillResult.fail(ex));

        FunctionResult result = fn.execute(FunctionInput.of(Map.of("userInput", "test")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("SKILL_ERROR");
        assertThat(result.getErrorMessage()).isEqualTo("skill crashed");
    }

    @Test
    @DisplayName("sessionId is passed through to SkillExecutionContext")
    void sessionIdPassedThrough() {
        skillRuntime.setResult(SkillResult.ok("ok"));

        fn.execute(FunctionInput.of(Map.of("userInput", "test", "sessionId", "sess-1")));

        assertThat(skillRuntime.lastCtx.getSessionId()).isEqualTo("sess-1");
    }

    @Test
    @DisplayName("traceId is passed through to SkillExecutionContext")
    void traceIdPassedThrough() {
        skillRuntime.setResult(SkillResult.ok("ok"));
        FunctionInput input = FunctionInput.builder()
                .parameters(Map.of("userInput", "test"))
                .traceId("trace-abc")
                .build();

        fn.execute(input);

        assertThat(skillRuntime.lastCtx.getTraceId()).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("getDefinition returns correct metadata")
    void definitionMetadata() {
        FunctionDefinition def = fn.getDefinition();

        assertThat(def.getName()).isEqualTo("skill:summary");
        assertThat(def.getCategory()).isEqualTo("skill");
        assertThat(def.isCallableByLLM()).isTrue();
        assertThat(def.getParameters()).hasSize(1);
        assertThat(def.getParameters().get(0).getName()).isEqualTo("userInput");
        assertThat(def.getParameters().get(0).isRequired()).isTrue();
    }

    @Test
    @DisplayName("getDefinition uses manifest description when available")
    void definitionFromManifest() {
        FunctionDefinition def = fn.getDefinition();

        assertThat(def.getDescription()).isEqualTo("A test summary skill");
    }

    @Test
    @DisplayName("getDefinition falls back when skill not found")
    void definitionFallback() {
        AgentSkillFunction unknown = new AgentSkillFunction("nonExist", skillRuntime,
                new StubSkillLoader() {
                    @Override
                    public Optional<SkillManifest> load(String name) {
                        return Optional.empty();
                    }
                });

        FunctionDefinition def = unknown.getDefinition();

        assertThat(def.getDescription()).isEqualTo("Execute skill: nonExist");
    }

    // ——— Stubs ———

    static class StubSkillLoader implements SkillLoader {
        @Override
        public Optional<SkillManifest> load(String skillName) {
            SkillManifest m = new SkillManifest();
            m.setName(skillName);
            m.setDescription("A test " + skillName + " skill");
            return Optional.of(m);
        }

        @Override
        public List<SkillManifest> loadAll() { return List.of(); }

        @Override
        public Optional<SkillManifest> reload(String skillName) { return load(skillName); }

        @Override
        public Set<String> listSkillNames() { return Set.of("summary"); }
    }

    static class StubSkillRuntime implements SkillRuntime {
        SkillResult result = SkillResult.ok("default output");
        String lastSkillName;
        SkillExecutionContext lastCtx;

        void setResult(SkillResult r) { this.result = r; }

        @Override
        public SkillResult execute(List<String> skillNames, SkillExecutionContext ctx) {
            this.lastSkillName = skillNames.get(skillNames.size() - 1);
            this.lastCtx = ctx;
            return result;
        }

        @Override
        public Flux<StreamPacket> executeStream(List<String> skillNames, SkillExecutionContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillResult execute(String skillName, SkillExecutionContext ctx) {
            return execute(List.of(skillName), ctx);
        }

        @Override
        public Flux<StreamPacket> executeStream(String skillName, SkillExecutionContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(String taskId) {}
    }
}
