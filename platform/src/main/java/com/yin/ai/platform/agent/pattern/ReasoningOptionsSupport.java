package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.gateway.ModelExecutionOptions;
import com.yin.ai.platform.gateway.model.ModelCapabilities;
import com.yin.ai.platform.gateway.reasoning.ReasoningPolicy;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.EnumSet;

/** Shared provider-neutral reasoning resolution for every Agent pattern. */
public final class ReasoningOptionsSupport {

    private static final ReasoningPolicy POLICY = new ReasoningPolicy();

    private ReasoningOptionsSupport() { }

    public static ModelExecutionOptions resolve(AgentExecutionContext ctx, StepType stepType) {
        ReasoningMode requested = ReasoningMode.fromString(String.valueOf(
                java.util.Objects.requireNonNullElse(ctx.getVariable("reasoningMode"), "AUTO")));
        ModelCapabilities capabilities = ctx.getVariable("modelCapabilities") instanceof ModelCapabilities value
                ? value
                : ModelCapabilities.reasoning(EnumSet.of(
                        ReasoningMode.LOW, ReasoningMode.MEDIUM, ReasoningMode.HIGH), 64_000);
        ModelExecutionOptions options = POLICY.resolve(ctx.getAgentMode(), stepType, requested, capabilities);
        ctx.putVariable("modelExecutionOptions", options);
        ctx.getAgentContext().putVariable("modelExecutionOptions", options);
        return options;
    }

    /** Apply an already-resolved effective level to Spring AI without exposing provider details upstream. */
    public static OpenAiChatOptions openAiOptions(AgentExecutionContext ctx, Double temperature) {
        ModelExecutionOptions execution = ctx.getVariable("modelExecutionOptions")
                instanceof ModelExecutionOptions value ? value : null;
        if (temperature == null && execution == null) return null;
        var builder = OpenAiChatOptions.builder();
        if (temperature != null
                && (execution == null || execution.effectiveReasoning() == ReasoningMode.OFF)) {
            builder.temperature(temperature);
        }
        if (execution != null && execution.effectiveReasoning() != ReasoningMode.OFF) {
            builder.reasoningEffort(execution.effectiveReasoning().name()
                    .toLowerCase(java.util.Locale.ROOT));
        }
        return builder.build();
    }
}
