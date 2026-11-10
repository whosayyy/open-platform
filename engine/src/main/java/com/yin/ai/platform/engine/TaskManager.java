package com.yin.ai.platform.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.engine.TaskRecoveryCallback;
import com.yin.ai.platform.config.PipelineConfig;
import com.yin.ai.platform.exception.TooManyTasksException;
import com.yin.ai.platform.engine.stream.SinkStreamProducer;
import com.yin.ai.platform.engine.stream.StreamMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * AI 会话任务治理中心。
 * 职责：任务生命周期管理、状态机驱动、超时熔断、并发防护、资源清理。
 * <p>
 * 超时调度器由 EngineConfiguration 统一注入，不再自行创建线程池。
 */
@Slf4j
@Component
public class TaskManager implements TaskCanceller {

    private static final String REDIS_TASKS_KEY = "ai:tasks";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();
    private final LongAdder activeCount = new LongAdder();
    private final ScheduledExecutorService timeoutScheduler;
    private final int maxConcurrency;
    private final long globalTimeoutMs;
    private final long firstTokenTimeoutMs;
    private final long toolExecutionTimeoutMs;
    private final SinkStreamProducer streamProducer;
    private final StreamMetrics metrics;
    private final RedissonClient redisson;
    private final String nodeId;
    private TaskRecoveryCallback recoveryCallback;

    public TaskManager(PipelineConfig config,
                       @Lazy SinkStreamProducer streamProducer,
                       StreamMetrics metrics,
                       ScheduledExecutorService engineTimeoutScheduler,
                       ObjectProvider<RedissonClient> redissonProvider) {
        this.maxConcurrency = config.getMaxConcurrency();
        this.globalTimeoutMs = config.getGlobalTimeoutMs();
        this.firstTokenTimeoutMs = config.getFirstTokenTimeoutMs();
        this.toolExecutionTimeoutMs = config.getToolExecutionTimeoutMs();
        this.streamProducer = streamProducer;
        this.metrics = metrics;
        this.timeoutScheduler = engineTimeoutScheduler;
        this.redisson = redissonProvider.getIfAvailable();
        this.nodeId = UUID.randomUUID().toString().substring(0, 8);

        log.info("[TASK-MGR] 初始化: maxConcurrency={}, globalTimeoutMs={}, firstTokenTimeoutMs={}, nodeId={}, redis={}",
                maxConcurrency, globalTimeoutMs, firstTokenTimeoutMs, nodeId,
                redisson != null ? "enabled" : "disabled");
    }

    public void setRecoveryCallback(TaskRecoveryCallback callback) {
        this.recoveryCallback = callback;
    }

    @PreDestroy
    public void shutdown() {
        log.info("[TASK-MGR] 开始关闭, activeCount={}", activeCount.sum());
        // 取消所有活跃任务的超时定时器
        for (Task task : tasks.values()) {
            try {
                task.forceTransitionTo(TaskState.CANCELLED);
            } catch (Exception ignored) { }
        }
        tasks.clear();
        activeCount.reset();
        log.info("[TASK-MGR] 关闭完成");
    }

    public Task createTask(String traceId) {
        long current = activeCount.sum();
        if (current >= maxConcurrency) {
            metrics.incrementRejectedTasks();
            throw new TooManyTasksException((int) current, maxConcurrency);
        }

        String taskId = UUID.randomUUID().toString();
        Task task = new Task(taskId, traceId);

        ScheduledFuture<?> globalTimeout = timeoutScheduler.schedule(
                () -> handleTimeout(taskId, TaskState.TIMEOUT),
                globalTimeoutMs, TimeUnit.MILLISECONDS
        );
        task.setGlobalTimeoutFuture(globalTimeout);

        tasks.put(taskId, task);
        activeCount.increment();
        registerTaskInRedis(task);
        log.info("Task 创建, taskId={}, activeCount={}", taskId, activeCount.sum());
        return task;
    }

    public boolean transitionTo(String taskId, TaskState target) {
        Task task = tasks.get(taskId);
        if (task == null) return false;
        boolean ok = task.transitionTo(target);
        if (ok) {
            task.touch(); // 状态转换时更新活跃时间
            log.debug("Task 状态转换, taskId={}, state={}", taskId, target);
            if (target.isTerminal()) {
                cleanup(task);
            }
        }
        return ok;
    }

    public void startFirstTokenTimeout(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null || task.isTerminal()) return;
        ScheduledFuture<?> f = timeoutScheduler.schedule(
                () -> handleTimeout(taskId, TaskState.TIMEOUT),
                firstTokenTimeoutMs, TimeUnit.MILLISECONDS
        );
        task.setFirstTokenTimeoutFuture(f);
    }

    public void cancelFirstTokenTimeout(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return;
        ScheduledFuture<?> f = task.getFirstTokenTimeoutFuture();
        if (f != null && !f.isDone()) f.cancel(false);
    }

    public ScheduledFuture<?> startToolTimeout(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null || task.isTerminal()) return null;
        ScheduledFuture<?> f = timeoutScheduler.schedule(
                () -> log.warn("工具执行超时, taskId={}", taskId),
                toolExecutionTimeoutMs, TimeUnit.MILLISECONDS
        );
        task.setToolTimeoutFuture(f);
        return f;
    }

    public void cancelToolTimeout(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return;
        ScheduledFuture<?> f = task.getToolTimeoutFuture();
        if (f != null && !f.isDone()) f.cancel(false);
    }

    public void cancel(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return;
        boolean ok = task.forceTransitionTo(TaskState.CANCELLED);
        if (!ok) return;
        log.info("Task 被用户取消, taskId={}", taskId);
        streamProducer.complete(taskId);
        cleanup(task);
    }

    public Task getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 更新任务最后活跃时间（Agent 每产生事件即调用），防止心跳扫描误清理长任务。
     */
    public void touchTask(String taskId) {
        Task task = tasks.get(taskId);
        if (task != null) {
            task.touch();
        }
    }

    public long getActiveCount() {
        return activeCount.sum();
    }

    /**
     * 获取当前活跃任务 ID 列表。
     */
    public List<String> getActiveTaskIds() {
        return new ArrayList<>(tasks.keySet());
    }

    /**
     * 尝试恢复任务 — 从检查点查询最后状态。
     *
     * @return 恢复信息（是否可恢复 + 最后步骤），若不可恢复返回 empty
     */
    public TaskRecoveryInfo recoverTask(String taskId) {
        if (recoveryCallback == null) {
            return TaskRecoveryInfo.notRecoverable();
        }
        return recoveryCallback.getLatestCheckpoint(taskId)
                .map(json -> TaskRecoveryInfo.recoverable("checkpoint available"))
                .orElse(TaskRecoveryInfo.notRecoverable());
    }

    /**
     * 心跳扫描 — 每 30s 检查活跃任务，清理孤儿任务。
     */
    @Scheduled(fixedDelay = 30_000)
    public void heartbeatScan() {
        long now = System.currentTimeMillis();
        for (Task task : tasks.values()) {
            if (task.isTerminal()) continue;
            // 更新 Redis 心跳
            updateHeartbeatInRedis(task.getTaskId(), now);

            // 优先按 lastActiveAt 判断（Agent 每产生事件即更新），fallback 到 createdAt
            long lastActive = task.getLastActiveAt() > 0 ? task.getLastActiveAt() : task.getCreatedAt();
            long idle = now - lastActive;
            // Agent 任务可能需要 60-120s，复杂任务更长。提高空闲超时至 10 分钟避免误清理。
            if (idle > TimeUnit.MINUTES.toMillis(10) && task.getState() == TaskState.RUNNING) {
                log.warn("[TASK-MGR] heartbeat: orphaned task detected taskId={} idleMs={} ageMs={}",
                        task.getTaskId(), idle, now - task.getCreatedAt());
                if (recoveryCallback != null) {
                    try {
                        recoveryCallback.deleteCheckpoints(task.getTaskId());
                    } catch (Exception e) {
                        log.debug("[TASK-MGR] checkpoint cleanup failed: {}", e.getMessage());
                    }
                }
                task.forceTransitionTo(TaskState.CANCELLED);
                streamProducer.complete(task.getTaskId());
                cleanup(task);
            }
        }
    }

    public record TaskRecoveryInfo(boolean recoverable, String detail) {
        static TaskRecoveryInfo recoverable(String detail) {
            return new TaskRecoveryInfo(true, detail);
        }
        static TaskRecoveryInfo notRecoverable() {
            return new TaskRecoveryInfo(false, "no checkpoint found");
        }
    }

    private void handleTimeout(String taskId, TaskState timeoutState) {
        Task task = tasks.get(taskId);
        if (task == null) return;
        boolean ok = task.forceTransitionTo(timeoutState);
        if (!ok) return;
        log.warn("Task 超时, taskId={}, state={}", taskId, timeoutState);
        streamProducer.emitError(taskId, "请求超时，请稍后重试");
        streamProducer.complete(taskId);
        cleanup(task);
    }

    private void cleanup(Task task) {
        String taskId = task.getTaskId();
        cancelFuture(task.getGlobalTimeoutFuture());
        cancelFuture(task.getFirstTokenTimeoutFuture());
        cancelFuture(task.getToolTimeoutFuture());
        tasks.remove(taskId);
        activeCount.decrement();
        removeTaskFromRedis(taskId);
        task.getCompletionFuture().complete(null);
        log.info("Task 清理完成, taskId={}, activeCount={}", taskId, activeCount.sum());
        metrics.logSnapshot();
    }

    private void cancelFuture(ScheduledFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    // ——— Redis 分布式任务注册 ———

    private void registerTaskInRedis(Task task) {
        if (redisson == null) return;
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("nodeId", nodeId);
            info.put("createdAt", task.getCreatedAt());
            info.put("lastActiveAt", task.getLastActiveAt());
            info.put("state", task.getState().name());
            String json = MAPPER.writeValueAsString(info);
            var map = redisson.getMap(REDIS_TASKS_KEY);
            map.put(task.getTaskId(), json);
            map.expire(30, TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            log.debug("[TASK-MGR] Redis registration failed for {}: {}", task.getTaskId(), e.getMessage());
        }
    }

    private void updateHeartbeatInRedis(String taskId, long now) {
        if (redisson == null) return;
        try {
            Object raw = redisson.getMap(REDIS_TASKS_KEY).get(taskId);
            if (raw == null) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> info = MAPPER.readValue(raw.toString(), Map.class);
            info.put("lastActiveAt", now);
            redisson.getMap(REDIS_TASKS_KEY).put(taskId, MAPPER.writeValueAsString(info));
        } catch (Exception e) {
            log.debug("[TASK-MGR] Redis heartbeat failed for {}: {}", taskId, e.getMessage());
        }
    }

    private void removeTaskFromRedis(String taskId) {
        if (redisson == null) return;
        try {
            redisson.getMap(REDIS_TASKS_KEY).remove(taskId);
        } catch (Exception ignored) { }
    }
}
