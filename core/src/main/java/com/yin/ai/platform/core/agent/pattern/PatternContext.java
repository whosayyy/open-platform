package com.yin.ai.platform.core.agent.pattern;

import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.approval.ApprovalPolicy;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpoint;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpointStore;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelOptions;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.tool.ToolDefinition;
import com.yin.ai.platform.core.tool.ToolRegistry;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable services and request state available to a core pattern. */
public record PatternContext(
        String taskId,
        AgentRequest request,
        ExecutionContext executionContext,
        ModelClient modelClient,
        ToolRegistry toolRegistry,
        List<ModelMessage> initialMessages,
        PatternEventSink events,
        AgentCheckpointStore checkpoints,
        ApprovalPolicy approvals
) {
    /** Compatibility constructor for non-resumable pattern callers. */
    public PatternContext(String taskId, AgentRequest request,
                          ExecutionContext executionContext, ModelClient modelClient,
                          ToolRegistry toolRegistry, List<ModelMessage> initialMessages,
                          PatternEventSink events) {
        this(taskId, request, executionContext, modelClient, toolRegistry,
                initialMessages, events, AgentCheckpointStore.NONE, ApprovalPolicy.NONE);
    }

    /** Compatibility constructor for callers that only configure checkpoints. */
    public PatternContext(String taskId, AgentRequest request,
                          ExecutionContext executionContext, ModelClient modelClient,
                          ToolRegistry toolRegistry, List<ModelMessage> initialMessages,
                          PatternEventSink events, AgentCheckpointStore checkpoints) {
        this(taskId, request, executionContext, modelClient, toolRegistry,
                initialMessages, events, checkpoints, ApprovalPolicy.NONE);
    }

    public PatternContext {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(executionContext, "executionContext");
        Objects.requireNonNull(modelClient, "modelClient");
        Objects.requireNonNull(toolRegistry, "toolRegistry");
        initialMessages = initialMessages == null ? List.of() : List.copyOf(initialMessages);
        events = events == null ? PatternEventSink.NOOP : events;
        checkpoints = checkpoints == null ? AgentCheckpointStore.NONE : checkpoints;
        approvals = approvals == null ? ApprovalPolicy.NONE : approvals;
    }

    /**
     * 调用模型。
     * <p>
     * 除了工具名之外，这里还会把完整的 {@link ToolDefinition}（含参数 schema）放进
     * {@link ModelRequest}，让模型适配器不需要自己持有 {@code ToolRegistry} 就能把
     * 工具的参数结构告诉模型。{@code allowedTools} 仍按调用方传入的名字做白名单过滤，
     * 因此可以安全地传入所有已注册定义。
     */
    public ModelResponse callModel(List<ModelMessage> messages, List<String> allowedTools,
                                   Map<String, Object> additionalMetadata) {
        executionContext.cancellationToken().throwIfCancellationRequested();
        Map<String, Object> metadata = new LinkedHashMap<>(request.metadata());
        if (additionalMetadata != null) metadata.putAll(additionalMetadata);
        ModelResponse response = modelClient.execute(new ModelRequest(messages,
                new ModelOptions(request.model(), request.temperature(), null, Map.of()),
                allowedTools, resolveToolDefinitions(allowedTools), metadata),
                executionContext);
        executionContext.cancellationToken().throwIfCancellationRequested();
        return response;
    }

    /** 按名字过滤出完整的工具定义；名字为空表示不限制。 */
    private List<ToolDefinition> resolveToolDefinitions(List<String> allowedTools) {
        Collection<ToolDefinition> all = toolRegistry.definitions();
        if (allowedTools == null || allowedTools.isEmpty()) {
            return List.copyOf(all);
        }
        return all.stream().filter(definition -> allowedTools.contains(definition.name())).toList();
    }

    public List<String> availableTools() {
        return toolRegistry.definitions().stream().map(ToolDefinition::name).toList();
    }

    public void emit(String type, Map<String, Object> payload) {
        try {
            events.emit(type, payload == null ? Map.of() : Map.copyOf(payload));
        } catch (RuntimeException ignored) {
            // Observers cannot abort an orchestration policy.
        }
    }

    public Optional<AgentCheckpoint> checkpoint() {
        return checkpoints.load(taskId);
    }

    public void saveCheckpoint(String mode, String phase, Map<String, Object> state) {
        long revision = checkpoint().map(value -> value.revision() + 1).orElse(1L);
        checkpoints.save(new AgentCheckpoint(taskId, mode, phase, revision, state, Instant.now()));
    }

    public void clearCheckpoint() {
        checkpoints.delete(taskId);
    }
}
