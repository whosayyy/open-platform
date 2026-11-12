package com.yin.ai.platform.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.engine.Task;
import com.yin.ai.platform.engine.TaskManager;
import com.yin.ai.platform.engine.TaskState;
import com.yin.ai.platform.engine.stream.SinkStreamProducer;
import com.yin.ai.platform.engine.stream.StreamMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Function 编排器 — 基于 {@link FunctionRegistry} 统一 Function 执行。
 * <p>
 * 当 Spring AI ChatClient 返回包含 tool_calls 的 ChatResponse 时
 * （internalToolExecutionEnabled=false），本组件接管工具执行流程：
 * <ol>
 *   <li>解析 ToolCall 对象（来自 Spring AI ChatResponse）</li>
 *   <li>流式推送 invoking/result 事件给前端</li>
 *   <li>通过 {@link FunctionRegistry} 并行执行 Function 调用</li>
 *   <li>Function 未注册时，尝试 {@link FunctionFallbackProvider} 回退</li>
 * </ol>
 * <p><b>高并发设计：</b>
 * <ul>
 *   <li>多 Function 并行执行，通过 CompletableFuture + 独立线程池</li>
 *   <li>Function 异常不中断会话——每个 Function 独立 try/catch</li>
 *   <li>执行前检查 Task.isTerminal()——用户可能已取消</li>
 * </ul>
 */
@Slf4j
public class FunctionOrchestrator {

    private final FunctionRegistry functionRegistry;
    private final List<FunctionFallbackProvider> fallbackProviders;
    private final SinkStreamProducer streamProducer;
    private final TaskManager taskManager;
    private final StreamMetrics metrics;
    private final ObjectMapper objectMapper;

    private final ExecutorService functionExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public FunctionOrchestrator(FunctionRegistry functionRegistry,
                                List<FunctionFallbackProvider> fallbackProviders,
                                SinkStreamProducer streamProducer,
                                TaskManager taskManager,
                                StreamMetrics metrics) {
        this.functionRegistry = functionRegistry;
        this.fallbackProviders = fallbackProviders != null
                ? fallbackProviders.stream().sorted(Comparator.comparingInt(FunctionFallbackProvider::order)).toList()
                : List.of();
        this.streamProducer = streamProducer;
        this.taskManager = taskManager;
        this.metrics = metrics;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 并行执行 Function 调用，同步等待所有 Function 完成。
     */
    public List<FunctionCallResult> executeAndEmit(String taskId,
                                                    List<FunctionCallRequest> requests,
                                                    SecurityContext securityContext) {
        if (requests == null || requests.isEmpty()) return List.of();

        List<CompletableFuture<FunctionCallResult>> futures = new ArrayList<>();

        for (FunctionCallRequest req : requests) {
            CompletableFuture<FunctionCallResult> future = CompletableFuture.supplyAsync(() -> {
                SecurityContext previousContext = null;
                if (securityContext != null && securityContext.getAuthentication() != null) {
                    previousContext = SecurityContextHolder.getContext();
                    SecurityContextHolder.setContext(securityContext);
                }
                try {
                    Task task = taskManager.getTask(taskId);
                    if (task != null && task.isTerminal()) {
                        return new FunctionCallResult(req.callId(), req.functionName(), null, false, "任务已终止");
                    }

                    Map<String, Object> args = parseArgs(req.arguments());

                    streamProducer.emitToolCall(taskId, "invoking", req.functionName(), args);
                    taskManager.transitionTo(taskId, TaskState.TOOL_CALLING);

                    taskManager.startToolTimeout(taskId);
                    FunctionResult fnResult = invokeFunction(req.functionName(), args);
                    taskManager.cancelToolTimeout(taskId);

                    Object result = fnResult.isSuccess()
                            ? fnResult.getData()
                            : fnResult.getErrorMessage();

                    streamProducer.emitToolCall(taskId, "result", req.functionName(), result);
                    return new FunctionCallResult(req.callId(), req.functionName(), result, fnResult.isSuccess(),
                            fnResult.isSuccess() ? null : fnResult.getErrorMessage());
                } catch (Exception e) {
                    metrics.incrementToolFailures();
                    log.warn("Function 执行失败, functionName={}, taskId={}", req.functionName(), taskId, e);
                    streamProducer.emitToolCallError(taskId, req.functionName(), e.getMessage());
                    taskManager.cancelToolTimeout(taskId);
                    return new FunctionCallResult(req.callId(), req.functionName(), null, false, e.getMessage());
                } finally {
                    if (previousContext != null) {
                        SecurityContextHolder.setContext(previousContext);
                    }
                }
            }, functionExecutor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<FunctionCallResult> results = new ArrayList<>();
        for (CompletableFuture<FunctionCallResult> f : futures) {
            try {
                results.add(f.get());
            } catch (Exception e) {
                log.warn("获取 Function 执行结果失败", e);
            }
        }
        return results;
    }

    private FunctionResult invokeFunction(String functionName, Map<String, Object> args) {
        try {
            return functionRegistry.invoke(functionName, args);
        } catch (IllegalArgumentException e) {
            for (FunctionFallbackProvider provider : fallbackProviders) {
                Optional<FunctionResult> result = provider.tryInvoke(functionName, args);
                if (result.isPresent()) {
                    return result.get();
                }
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(argumentsJson, Map.class);
        } catch (Exception e) {
            log.warn("Function 参数 JSON 解析失败: {}", argumentsJson);
            return Map.of();
        }
    }

    public record FunctionCallResult(String callId, String functionName, Object result,
                                      boolean success, String errorMessage) {}

    public record FunctionCallRequest(String callId, String functionName, String arguments) {}
}
