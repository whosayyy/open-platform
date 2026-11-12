package com.yin.ai.platform.adapter.runtime.persistence;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.task.StepExecutionRecord;
import com.yin.ai.platform.agent.task.TaskCreateRequest;
import com.yin.ai.platform.agent.task.TaskEngine;
import com.yin.ai.platform.agent.task.TaskHandle;
import com.yin.ai.platform.agent.task.TaskSnapshot;
import com.yin.ai.platform.agent.task.TaskState;
import com.yin.ai.platform.agent.task.TaskStepResult;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Redis adapter for the legacy Agent Runtime task lifecycle port. */
public class RedisTaskEngine implements TaskEngine {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskEngine.class);
    private static final String KEY_PREFIX = "ai:task:";
    private static final String STORAGE_VERSION = "v2";
    private static final Duration TERMINAL_TTL = Duration.ofHours(24);

    private final RedissonClient redisson;
    private final EventPublisher eventPublisher;
    private final ConcurrentHashMap<String, TaskState> localStateCache = new ConcurrentHashMap<>();

    public RedisTaskEngine(RedissonClient redisson, EventPublisher eventPublisher) {
        this.redisson = redisson;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public TaskHandle create(TaskCreateRequest request) {
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, String> metadata = request.metadata() != null ? request.metadata() : Map.of();
        String parentTaskId = blankToNull(metadata.get("parentTaskId"));
        String rootTaskId = blankToNull(metadata.get("rootTaskId"));
        if (rootTaskId == null) rootTaskId = parentTaskId != null ? parentTaskId : taskId;
        String agentRole = blankToNull(metadata.get("agentRole"));
        Map<String, Object> variables = new java.util.LinkedHashMap<>(metadata);
        TaskSnapshot snapshot = new TaskSnapshot(
                taskId, request.goal(), request.userId(), request.sessionId(),
                request.mode(), TaskState.CREATED, null, 0,
                Map.copyOf(variables), now, now, null,
                parentTaskId, rootTaskId, agentRole,
                parentTaskId == null ? metadata.get("teamStatus") : null
        );
        saveMeta(taskId, snapshot);
        setState(taskId, TaskState.CREATED);
        if (parentTaskId != null) {
            redisson.<String>getList(versionedKey(parentTaskId, "children"), StringCodec.INSTANCE).add(taskId);
        }
        log.info("[TASK] created taskId={} mode={} user={}", taskId, request.mode(), request.userId());
        return new TaskHandle(taskId, request.mode(), TaskState.CREATED, now);
    }

    @Override
    public void start(String taskId) {
        TaskSnapshot metadata = getSnapshot(taskId);
        if (metadata == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        transitionTo(taskId, metadata.mode().resumable() ? TaskState.PLANNING : TaskState.RUNNING);
    }

    @Override
    public void transitionTo(String taskId, TaskState newState) {
        TaskState current = getState(taskId);
        if (current == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!current.canTransitionTo(newState)) {
            throw new IllegalStateException(
                    "Illegal transition for task " + taskId + ": " + current + " → " + newState);
        }
        setState(taskId, newState);
        updateSnapshotState(taskId, newState);
        publishStateEvent(taskId, newState);
        if (newState.isTerminal()) {
            expireTask(taskId);
            localStateCache.remove(taskId);
        }
        log.info("[TASK] transition taskId={} {} → {}", taskId, current, newState);
    }

    @Override
    public TaskSnapshot getSnapshot(String taskId) {
        TaskSnapshot snapshot = redisson.<TaskSnapshot>getBucket(
                versionedKey(taskId, "meta"), LegacyAgentStateJsonCodec.INSTANCE).get();
        if (snapshot != null) return snapshot;
        snapshot = redisson.<TaskSnapshot>getBucket(KEY_PREFIX + taskId + ":meta").get();
        if (snapshot != null) saveMeta(taskId, snapshot);
        return snapshot;
    }

    @Override
    public void saveStepResult(String taskId, String stepId, TaskStepResult result) {
        redisson.getMap(versionedKey(taskId, "steps"), LegacyAgentStateJsonCodec.INSTANCE)
                .put(stepId, result);
        log.debug("[TASK] stepResult taskId={} step={} success={}", taskId, stepId, result.success());
    }

    @Override
    public boolean tryAcquireStepExecution(String taskId, String executionId, Duration ttl) {
        if (executionId == null || executionId.isBlank()) return false;
        Duration leaseTtl = ttl == null || ttl.isNegative() || ttl.isZero()
                ? Duration.ofMinutes(15) : ttl;
        Object previous = redisson.getMapCache(
                        versionedKey(taskId, "step-leases"), StringCodec.INSTANCE)
                .putIfAbsent(executionId, "RUNNING", leaseTtl.toMillis(), TimeUnit.MILLISECONDS);
        return previous == null;
    }

    @Override
    public void saveStructuredStepResult(String taskId,
                                         com.yin.ai.platform.agent.pattern.StepResult result) {
        if (result == null || result.stepId() == null) return;
        redisson.getMap(versionedKey(taskId, "structured-results"), LegacyAgentStateJsonCodec.INSTANCE)
                .put(result.stepId(), result);
    }

    @Override
    public void enqueueInstruction(String taskId, String instruction) {
        if (instruction == null || instruction.isBlank()) return;
        redisson.<String>getList(versionedKey(taskId, "instructions"), StringCodec.INSTANCE)
                .add(instruction.trim());
    }

    @Override
    public List<String> drainInstructions(String taskId) {
        String key = versionedKey(taskId, "instructions");
        var lock = redisson.getLock(key + ":drain-lock");
        lock.lock();
        try {
            var list = redisson.<String>getList(key, StringCodec.INSTANCE);
            List<String> values = list.readAll();
            if (!values.isEmpty()) list.clear();
            return values;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public StepExecutionRecord getStepExecutionRecord(String taskId, String stepId) {
        if (stepId == null) return null;
        return (StepExecutionRecord) redisson.getMap(
                versionedKey(taskId, "step-records"), LegacyAgentStateJsonCodec.INSTANCE).get(stepId);
    }

    @Override
    public void saveStepExecutionRecord(String taskId, StepExecutionRecord record) {
        if (record == null || record.stepId() == null) return;
        redisson.getMap(versionedKey(taskId, "step-records"), LegacyAgentStateJsonCodec.INSTANCE)
                .put(record.stepId(), record);
    }

    @Override
    public List<StepExecutionRecord> listStepExecutionRecords(String taskId) {
        return redisson.<String, StepExecutionRecord>getMap(
                        versionedKey(taskId, "step-records"), LegacyAgentStateJsonCodec.INSTANCE)
                .readAllValues().stream().toList();
    }

    @Override
    public boolean tryResume(String taskId) {
        TaskSnapshot metadata = getSnapshot(taskId);
        if (metadata == null) {
            log.warn("[TASK] resume failed: task not found {}", taskId);
            return false;
        }
        if (!metadata.mode().resumable()) {
            log.warn("[TASK] resume failed: mode {} not resumable", metadata.mode());
            return false;
        }
        TaskState current = getState(taskId);
        if (current != TaskState.FAILED && current != TaskState.RUNNING && current != TaskState.WAITING) {
            log.warn("[TASK] resume failed: state {} not resumable", current);
            return false;
        }
        setState(taskId, TaskState.RUNNING);
        updateSnapshotState(taskId, TaskState.RUNNING);
        publishStateEvent(taskId, TaskEventType.TASK_RESUMED);
        log.info("[TASK] resumed taskId={} from step {}", taskId, metadata.stepIndex());
        return true;
    }

    @Override
    public void cancel(String taskId, String reason) {
        TaskState current = getState(taskId);
        if (current == null || current.isTerminal()) return;
        setState(taskId, TaskState.CANCELLED);
        updateSnapshotState(taskId, TaskState.CANCELLED);
        publishStateEvent(taskId, TaskState.CANCELLED);
        expireTask(taskId);
        localStateCache.remove(taskId);
        log.info("[TASK] cancelled taskId={} reason={}", taskId, reason);
    }

    @Override
    public List<TaskSnapshot> listChildren(String parentTaskId) {
        if (parentTaskId == null || parentTaskId.isBlank()) return List.of();
        List<String> childIds = redisson.<String>getList(
                versionedKey(parentTaskId, "children"), StringCodec.INSTANCE).readAll();
        if (childIds.isEmpty()) {
            childIds = redisson.<String>getList(KEY_PREFIX + parentTaskId + ":children").readAll();
        }
        return childIds.stream().map(this::getSnapshot).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public void updateTeamStatus(String taskId, String teamStatus) {
        RBucket<TaskSnapshot> bucket = redisson.getBucket(
                versionedKey(taskId, "meta"), LegacyAgentStateJsonCodec.INSTANCE);
        TaskSnapshot current = bucket.get();
        if (current != null) bucket.set(copy(current, current.state(), teamStatus));
    }

    private TaskState getState(String taskId) {
        TaskState cached = localStateCache.get(taskId);
        if (cached != null) return cached;
        RBucket<String> bucket = redisson.getBucket(versionedKey(taskId, "state"), StringCodec.INSTANCE);
        String value = bucket.get();
        if (value == null) {
            value = redisson.<String>getBucket(KEY_PREFIX + taskId + ":state").get();
            if (value != null) bucket.set(value);
        }
        if (value == null) return null;
        TaskState state = TaskState.valueOf(value);
        localStateCache.put(taskId, state);
        return state;
    }

    private void setState(String taskId, TaskState state) {
        redisson.getBucket(versionedKey(taskId, "state"), StringCodec.INSTANCE).set(state.name());
        localStateCache.put(taskId, state);
    }

    private void saveMeta(String taskId, TaskSnapshot snapshot) {
        redisson.getBucket(versionedKey(taskId, "meta"), LegacyAgentStateJsonCodec.INSTANCE).set(snapshot);
    }

    private void updateSnapshotState(String taskId, TaskState state) {
        RBucket<TaskSnapshot> bucket = redisson.getBucket(
                versionedKey(taskId, "meta"), LegacyAgentStateJsonCodec.INSTANCE);
        TaskSnapshot current = bucket.get();
        if (current != null) bucket.set(copy(current, state, current.teamStatus()));
    }

    private void expireTask(String taskId) {
        redisson.getBucket(versionedKey(taskId, "state"), StringCodec.INSTANCE).expire(TERMINAL_TTL);
        redisson.getBucket(versionedKey(taskId, "meta"), LegacyAgentStateJsonCodec.INSTANCE)
                .expire(TERMINAL_TTL);
        redisson.getMap(versionedKey(taskId, "steps"), LegacyAgentStateJsonCodec.INSTANCE)
                .expire(TERMINAL_TTL);
        redisson.getMap(versionedKey(taskId, "step-records"), LegacyAgentStateJsonCodec.INSTANCE)
                .expire(TERMINAL_TTL);
        redisson.getMapCache(versionedKey(taskId, "step-leases"), StringCodec.INSTANCE)
                .expire(TERMINAL_TTL);
        redisson.getMap(versionedKey(taskId, "structured-results"), LegacyAgentStateJsonCodec.INSTANCE)
                .expire(TERMINAL_TTL);
        redisson.getList(versionedKey(taskId, "instructions"), StringCodec.INSTANCE)
                .expire(TERMINAL_TTL);
        redisson.getList(versionedKey(taskId, "children"), StringCodec.INSTANCE)
                .expire(TERMINAL_TTL);
        redisson.getBucket(KEY_PREFIX + taskId + ":state").expire(TERMINAL_TTL);
        redisson.getBucket(KEY_PREFIX + taskId + ":meta").expire(TERMINAL_TTL);
    }

    private TaskSnapshot copy(TaskSnapshot current, TaskState state, String teamStatus) {
        return new TaskSnapshot(
                current.taskId(), current.goal(), current.userId(), current.sessionId(),
                current.mode(), state, current.plan(), current.stepIndex(), current.variables(),
                current.createdAt(), Instant.now(), current.error(), current.parentTaskId(),
                current.rootTaskId(), current.agentRole(), teamStatus);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String versionedKey(String taskId, String suffix) {
        return KEY_PREFIX + taskId + ":" + suffix + ":" + STORAGE_VERSION;
    }

    private void publishStateEvent(String taskId, TaskState state) {
        TaskEventType eventType = switch (state) {
            case PLANNING -> TaskEventType.TASK_PLANNING;
            case RUNNING -> TaskEventType.TASK_RUNNING;
            case WAITING -> TaskEventType.TASK_WAITING;
            case COMPLETED -> TaskEventType.TASK_COMPLETED;
            case FAILED -> TaskEventType.TASK_FAILED;
            case CANCELLED -> TaskEventType.TASK_CANCELLED;
            default -> TaskEventType.PROGRESS;
        };
        publishStateEvent(taskId, eventType);
    }

    private void publishStateEvent(String taskId, TaskEventType eventType) {
        eventPublisher.publish(new AgentEvent(
                null, taskId, eventType, null, Map.of(), Instant.now()));
    }
}
