package com.yin.ai.platform.adapter.runtime.persistence;

import com.yin.ai.platform.agent.task.TaskTokenAccountant;
import com.yin.ai.platform.agent.task.TaskTokenBudget;
import com.yin.ai.platform.agent.task.TaskTokenSummary;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Redis adapter for legacy task token accounting, with a local fallback. */
public class RedisTaskTokenAccountant implements TaskTokenAccountant {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskTokenAccountant.class);
    private static final String PREFIX = "ai:task:";
    private static final String STORAGE_VERSION = "v2";
    private static final Duration TTL = Duration.ofHours(48);

    private final RedissonClient redisson;
    private final Map<String, Map<String, Long>> local = new ConcurrentHashMap<>();
    private final Map<String, TaskTokenBudget> localBudgets = new ConcurrentHashMap<>();

    public RedisTaskTokenAccountant(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public void setBudget(String taskId, TaskTokenBudget budget) {
        TaskTokenBudget effective = budget != null ? budget : TaskTokenBudget.DEFAULT;
        if (redisson == null) {
            localBudgets.put(taskId, effective);
            return;
        }
        try {
            RMap<String, String> map = budgetMap(taskId);
            map.put("maxTotalTokens", String.valueOf(effective.maxTotalTokens()));
            map.put("maxTokensPerStep", String.valueOf(effective.maxTokensPerStep()));
            map.put("maxLlmCallsPerStep", String.valueOf(effective.maxLlmCallsPerStep()));
            map.put("warningThresholdPercent", String.valueOf(effective.warningThresholdPercent()));
            map.expire(TTL);
        } catch (RuntimeException e) {
            localBudgets.put(taskId, effective);
            log.warn("[TOKEN-ACCOUNTING] Redis budget unavailable; using local fallback taskId={}: {}",
                    taskId, e.getMessage());
        }
    }

    @Override
    public void record(String taskId, String stepId, String model, long totalTokens) {
        if (taskId == null || totalTokens <= 0) return;
        String effectiveStep = stepId == null || stepId.isBlank() ? "_task" : stepId;
        String effectiveModel = model == null || model.isBlank() ? "unknown" : model;
        if (redisson == null) {
            recordLocal(taskId, effectiveStep, effectiveModel, totalTokens);
            return;
        }
        try {
            RMap<String, Long> map = tokenMap(taskId);
            map.addAndGet("total", totalTokens);
            map.addAndGet("calls", 1L);
            map.addAndGet("step.tokens." + effectiveStep, totalTokens);
            map.addAndGet("step.calls." + effectiveStep, 1L);
            map.addAndGet("model.tokens." + effectiveModel, totalTokens);
            map.expire(TTL);
        } catch (RuntimeException e) {
            recordLocal(taskId, effectiveStep, effectiveModel, totalTokens);
            log.warn("[TOKEN-ACCOUNTING] Redis write unavailable; using local fallback taskId={}: {}",
                    taskId, e.getMessage());
        }
    }

    @Override
    public TaskTokenSummary getSummary(String taskId) {
        Map<String, Long> values = new HashMap<>(local.getOrDefault(taskId, Map.of()));
        if (redisson != null) {
            try {
                tokenMap(taskId).readAllMap().forEach((key, value) -> values.merge(key, value, Long::sum));
            } catch (RuntimeException e) {
                log.warn("[TOKEN-ACCOUNTING] Redis read unavailable; continuing with local counters taskId={}: {}",
                        taskId, e.getMessage());
            }
        }
        TaskTokenBudget budget = getBudget(taskId);
        long total = values.getOrDefault("total", 0L);
        Map<String, Long> byStep = extract(values, "step.tokens.");
        Map<String, Long> callsByStep = extract(values, "step.calls.");
        Map<String, Long> byModel = extract(values, "model.tokens.");
        boolean warning = total * 100 >= budget.maxTotalTokens() * budget.warningThresholdPercent();
        return new TaskTokenSummary(taskId, total, values.getOrDefault("calls", 0L),
                Map.copyOf(byStep), Map.copyOf(callsByStep), Map.copyOf(byModel),
                warning, total >= budget.maxTotalTokens());
    }

    @Override
    public boolean isOverBudget(String taskId) {
        return getSummary(taskId).budgetExceeded();
    }

    @Override
    public boolean canExecuteStep(String taskId, String stepId) {
        TaskTokenSummary summary = getSummary(taskId);
        TaskTokenBudget budget = getBudget(taskId);
        String effectiveStep = stepId == null || stepId.isBlank() ? "_task" : stepId;
        return !summary.budgetExceeded()
                && summary.tokensByStep().getOrDefault(effectiveStep, 0L) < budget.maxTokensPerStep()
                && summary.callsByStep().getOrDefault(effectiveStep, 0L) < budget.maxLlmCallsPerStep();
    }

    @Override
    public void cleanup(String taskId) {
        local.remove(taskId);
        localBudgets.remove(taskId);
        if (redisson != null) {
            redisson.getKeys().delete(
                    key(taskId, "tokens"), key(taskId, "token-budget"),
                    versionedKey(taskId, "tokens"), versionedKey(taskId, "token-budget"));
        }
    }

    private TaskTokenBudget getBudget(String taskId) {
        if (redisson == null) {
            return localBudgets.getOrDefault(taskId, TaskTokenBudget.DEFAULT);
        }
        Map<String, String> values;
        try {
            values = budgetMap(taskId).readAllMap();
        } catch (RuntimeException e) {
            log.warn("[TOKEN-ACCOUNTING] Redis budget read unavailable; using defaults taskId={}: {}",
                    taskId, e.getMessage());
            return localBudgets.getOrDefault(taskId, TaskTokenBudget.DEFAULT);
        }
        if (values.isEmpty()) return TaskTokenBudget.DEFAULT;
        return new TaskTokenBudget(
                parseLong(values.get("maxTotalTokens"), TaskTokenBudget.DEFAULT.maxTotalTokens()),
                parseLong(values.get("maxTokensPerStep"), TaskTokenBudget.DEFAULT.maxTokensPerStep()),
                (int) parseLong(values.get("maxLlmCallsPerStep"), TaskTokenBudget.DEFAULT.maxLlmCallsPerStep()),
                (int) parseLong(values.get("warningThresholdPercent"),
                        TaskTokenBudget.DEFAULT.warningThresholdPercent()));
    }

    private Map<String, Long> extract(Map<String, Long> values, String prefix) {
        Map<String, Long> result = new HashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(prefix)) result.put(key.substring(prefix.length()), value);
        });
        return result;
    }

    private long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void recordLocal(String taskId, String stepId, String model, long totalTokens) {
        Map<String, Long> values = local.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>());
        values.merge("total", totalTokens, Long::sum);
        values.merge("calls", 1L, Long::sum);
        values.merge("step.tokens." + stepId, totalTokens, Long::sum);
        values.merge("step.calls." + stepId, 1L, Long::sum);
        values.merge("model.tokens." + model, totalTokens, Long::sum);
    }

    private RMap<String, Long> tokenMap(String taskId) {
        return redisson.getMap(versionedKey(taskId, "tokens"), LegacyAgentStateJsonCodec.INSTANCE);
    }

    private RMap<String, String> budgetMap(String taskId) {
        return redisson.getMap(versionedKey(taskId, "token-budget"), LegacyAgentStateJsonCodec.INSTANCE);
    }

    private String versionedKey(String taskId, String suffix) {
        return key(taskId, suffix) + ":" + STORAGE_VERSION;
    }

    private String key(String taskId, String suffix) {
        return PREFIX + taskId + ":" + suffix;
    }
}
