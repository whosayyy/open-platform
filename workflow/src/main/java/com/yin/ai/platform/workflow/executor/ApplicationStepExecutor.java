package com.yin.ai.platform.workflow.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.workflow.model.Artifact;
import com.yin.ai.platform.workflow.model.ArtifactType;
import com.yin.ai.platform.workflow.model.ExecutorType;
import com.yin.ai.platform.workflow.runtime.StepIdempotencyStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 应用步骤执行器 — 确定性业务操作（保存草稿/发布等），不调用 LLM。
 */
@Slf4j
@Component
public class ApplicationStepExecutor implements StepExecutor {

    private final ApplicationStepHandlerRegistry handlerRegistry;
    private final StepIdempotencyStore idempotencyStore;
    private final CompensationHandlerRegistry compensationRegistry;
    private final ObjectMapper objectMapper;

    public ApplicationStepExecutor(ApplicationStepHandlerRegistry handlerRegistry) {
        this(handlerRegistry, null, null, new ObjectMapper());
    }

    @Autowired
    public ApplicationStepExecutor(ApplicationStepHandlerRegistry handlerRegistry,
                                   StepIdempotencyStore idempotencyStore,
                                   CompensationHandlerRegistry compensationRegistry,
                                   ObjectMapper objectMapper) {
        this.handlerRegistry = handlerRegistry;
        this.idempotencyStore = idempotencyStore;
        this.compensationRegistry = compensationRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExecutorType type() {
        return ExecutorType.APPLICATION;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext context) {
        String handlerName = context.step().getHandler();
        if (handlerName == null || handlerName.isBlank()) {
            return StepExecutionResult.fail("NO_HANDLER", "APPLICATION step requires handler");
        }
        ApplicationStepHandler handler = handlerRegistry.get(handlerName).orElse(null);
        if (handler == null) {
            return StepExecutionResult.fail("NO_HANDLER", "no handler registered: " + handlerName);
        }
        StepIdempotencyStore.BeginResult begin = idempotencyStore != null
                ? idempotencyStore.begin(context.taskRun().getTaskRunId(), context.step().getId())
                : StepIdempotencyStore.BeginResult.ACQUIRED;
        if (begin == StepIdempotencyStore.BeginResult.IN_PROGRESS) {
            return StepExecutionResult.fail("STEP_IN_PROGRESS", "application step is already executing");
        }
        if (begin == StepIdempotencyStore.BeginResult.COMMITTED) {
            return restoreCommitted(context);
        }

        HandlerResult result;
        try {
            result = handler.execute(context);
        } catch (RuntimeException e) {
            compensate(handlerName, context, e);
            if (idempotencyStore != null) {
                idempotencyStore.release(context.taskRun().getTaskRunId(), context.step().getId());
            }
            return StepExecutionResult.fail("HANDLER_EXCEPTION", e.getMessage());
        }
        if (!result.success()) {
            compensate(handlerName, context, new IllegalStateException(result.errorMessage()));
            if (idempotencyStore != null) {
                idempotencyStore.release(context.taskRun().getTaskRunId(), context.step().getId());
            }
            return StepExecutionResult.fail(
                    result.errorCode() == null ? "HANDLER_FAILED" : result.errorCode(),
                    result.errorMessage());
        }
        ArtifactType outputType = context.step().outputType();
        if (outputType == null) {
            commit(context, result);
            return StepExecutionResult.ok(null);
        }
        Artifact artifact = Artifact.of(context.taskRun().getTaskRunId(),
                context.step().getId(), outputType, result.data());
        commit(context, result);
        return StepExecutionResult.ok(artifact);
    }

    private void commit(StepExecutionContext context, HandlerResult result) {
        if (idempotencyStore == null) return;
        try {
            idempotencyStore.commit(context.taskRun().getTaskRunId(), context.step().getId(),
                    objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException("failed to persist application step commit", e);
        }
    }

    private StepExecutionResult restoreCommitted(StepExecutionContext context) {
        try {
            String json = idempotencyStore.loadCommittedResult(
                    context.taskRun().getTaskRunId(), context.step().getId());
            HandlerResult result = json == null || "null".equals(json)
                    ? HandlerResult.ok(null) : objectMapper.readValue(json, HandlerResult.class);
            ArtifactType outputType = context.step().outputType();
            Artifact artifact = outputType == null ? null : Artifact.of(context.taskRun().getTaskRunId(),
                    context.step().getId(), outputType, result.data());
            return StepExecutionResult.ok(artifact);
        } catch (Exception e) {
            return StepExecutionResult.fail("IDEMPOTENCY_RESULT_CORRUPT", e.getMessage());
        }
    }

    private void compensate(String handlerName, StepExecutionContext context, Throwable cause) {
        if (compensationRegistry == null) return;
        compensationRegistry.get(handlerName).ifPresent(handler -> {
            try {
                handler.compensate(context, cause);
            } catch (RuntimeException compensationError) {
                log.error("[WF-APP] compensation failed handler={} taskRunId={}: {}",
                        handlerName, context.taskRun().getTaskRunId(), compensationError.getMessage());
            }
        });
    }
}
