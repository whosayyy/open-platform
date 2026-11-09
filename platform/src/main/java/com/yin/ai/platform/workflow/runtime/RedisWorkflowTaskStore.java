package com.yin.ai.platform.workflow.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.workflow.model.Artifact;
import com.yin.ai.platform.workflow.model.ArtifactType;
import com.yin.ai.platform.workflow.model.StepRun;
import com.yin.ai.platform.workflow.model.TaskRun;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Redis 的工作流任务存储。
 *
 * <p>Key 设计（TTL 48h）：
 * <ul>
 *   <li>{@code ai:wf:task:{taskRunId}} — TaskRun JSON</li>
 *   <li>{@code ai:wf:task:{taskRunId}:steps} — List&lt;StepRun&gt; JSON</li>
 *   <li>{@code ai:wf:task:{taskRunId}:artifacts} — List&lt;Artifact&gt; JSON</li>
 * </ul>
 *
 * <p>Redisson 不可用时自动降级为进程内存储（测试/本地环境）。
 */
@Slf4j
public class RedisWorkflowTaskStore implements WorkflowTaskStore {

    private static final String KEY_PREFIX = "ai:wf:task:";
    private static final Duration TTL = Duration.ofHours(48);

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final boolean useRedis;

    /** 降级用的进程内存储 */
    private final Map<String, TaskRun> memoryTasks = new ConcurrentHashMap<>();
    private final Map<String, List<StepRun>> memorySteps = new ConcurrentHashMap<>();
    private final Map<String, List<Artifact>> memoryArtifacts = new ConcurrentHashMap<>();

    public RedisWorkflowTaskStore(RedissonClient redisson, ObjectMapper objectMapper) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.useRedis = redisson != null;
    }

    @Override
    public void saveTaskRun(TaskRun taskRun) {
        if (!useRedis) {
            memoryTasks.put(taskRun.getTaskRunId(), deepCopy(taskRun));
            return;
        }
        RBucket<TaskRun> bucket = redisson.getBucket(KEY_PREFIX + taskRun.getTaskRunId());
        bucket.set(taskRun, TTL);
    }

    @Override
    public void updateTaskRun(TaskRun taskRun) {
        saveTaskRun(taskRun);
    }

    @Override
    public TaskRun loadTaskRun(String taskRunId) {
        if (!useRedis) {
            return deepCopy(memoryTasks.get(taskRunId));
        }
        RBucket<TaskRun> bucket = redisson.getBucket(KEY_PREFIX + taskRunId);
        return bucket.get();
    }

    @Override
    public void saveStepRun(StepRun stepRun) {
        List<StepRun> steps;
        if (!useRedis) {
            steps = memorySteps.computeIfAbsent(stepRun.getTaskRunId(), k -> new ArrayList<>());
        } else {
            steps = loadStepRunList(stepRun.getTaskRunId());
        }
        steps.removeIf(s -> s.getStepRunId().equals(stepRun.getStepRunId()));
        steps.add(stepRun);
        if (!useRedis) {
            return;
        }
        persistStepRunList(stepRun.getTaskRunId(), steps);
    }

    @Override
    public void updateStepRun(StepRun stepRun) {
        saveStepRun(stepRun);
    }

    @Override
    public List<StepRun> listStepRuns(String taskRunId) {
        return loadStepRunList(taskRunId);
    }

    @Override
    public void saveArtifact(Artifact artifact) {
        if (!useRedis) {
            List<Artifact> list = memoryArtifacts.computeIfAbsent(artifact.taskRunId(), k -> new ArrayList<>());
            list.removeIf(a -> a.type() == artifact.type());
            list.add(artifact);
            return;
        }
        List<Artifact> list = loadArtifactList(artifact.taskRunId());
        list.removeIf(a -> a.type() == artifact.type());
        list.add(artifact);
        persistArtifactList(artifact.taskRunId(), list);
    }

    @Override
    public List<Artifact> listArtifacts(String taskRunId) {
        return loadArtifactList(taskRunId);
    }

    @Override
    public Artifact loadArtifact(String taskRunId, ArtifactType type) {
        return loadArtifactList(taskRunId).stream()
                .filter(a -> a.type() == type)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void cleanup(String taskRunId) {
        if (!useRedis) {
            memoryTasks.remove(taskRunId);
            memorySteps.remove(taskRunId);
            memoryArtifacts.remove(taskRunId);
            return;
        }
        try {
            redisson.getKeys().deleteByPattern(KEY_PREFIX + taskRunId + ":*");
            redisson.getKeys().delete(KEY_PREFIX + taskRunId);
        } catch (Exception e) {
            log.warn("[WF-STORE] cleanup failed taskRunId={}: {}", taskRunId, e.getMessage());
        }
    }

    private List<StepRun> loadStepRunList(String taskRunId) {
        if (!useRedis) {
            return new ArrayList<>(memorySteps.getOrDefault(taskRunId, new ArrayList<>()));
        }
        RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + taskRunId + ":steps");
        String json = bucket.get();
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<StepRun>>() {
            });
        } catch (Exception e) {
            log.warn("[WF-STORE] parse steps failed taskRunId={}: {}", taskRunId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void persistStepRunList(String taskRunId, List<StepRun> steps) {
        try {
            RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + taskRunId + ":steps");
            bucket.set(objectMapper.writeValueAsString(steps), TTL);
        } catch (Exception e) {
            log.warn("[WF-STORE] persist steps failed taskRunId={}: {}", taskRunId, e.getMessage());
        }
    }

    private List<Artifact> loadArtifactList(String taskRunId) {
        if (!useRedis) {
            return new ArrayList<>(memoryArtifacts.getOrDefault(taskRunId, new ArrayList<>()));
        }
        RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + taskRunId + ":artifacts");
        String json = bucket.get();
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Artifact>>() {
            });
        } catch (Exception e) {
            log.warn("[WF-STORE] parse artifacts failed taskRunId={}: {}", taskRunId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void persistArtifactList(String taskRunId, List<Artifact> artifacts) {
        try {
            RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + taskRunId + ":artifacts");
            bucket.set(objectMapper.writeValueAsString(artifacts), TTL);
        } catch (Exception e) {
            log.warn("[WF-STORE] persist artifacts failed taskRunId={}: {}", taskRunId, e.getMessage());
        }
    }

    private static TaskRun deepCopy(TaskRun source) {
        if (source == null) {
            return null;
        }
        TaskRun copy = new TaskRun();
        copy.setTaskRunId(source.getTaskRunId());
        copy.setConversationId(source.getConversationId());
        copy.setUserId(source.getUserId());
        copy.setWorkflowName(source.getWorkflowName());
        copy.setWorkflowVersion(source.getWorkflowVersion());
        copy.setStatus(source.getStatus());
        copy.setCurrentStepId(source.getCurrentStepId());
        copy.setExplicitPublishRequested(source.isExplicitPublishRequested());
        copy.setStageByStageMode(source.isStageByStageMode());
        copy.setRetryCount(source.getRetryCount());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setCompletedAt(source.getCompletedAt());
        copy.setTraceId(source.getTraceId());
        copy.setGoal(source.getGoal());
        copy.setWaitingInputReason(source.getWaitingInputReason());
        copy.setContext(source.getContext() == null ? new HashMap<>() : new HashMap<>(source.getContext()));
        return copy;
    }
}
