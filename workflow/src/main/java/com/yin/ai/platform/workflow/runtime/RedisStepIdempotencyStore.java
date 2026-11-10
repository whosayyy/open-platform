package com.yin.ai.platform.workflow.runtime;

import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Redis idempotency state with process-local fallback. */
public class RedisStepIdempotencyStore implements StepIdempotencyStore {

    private static final String PREFIX = "ai:wf:idempotency:";
    private static final Duration TTL = Duration.ofHours(48);
    private static final String STARTED = "STARTED:";
    private static final String COMMITTED = "COMMITTED";
    private static final long LEASE_MS = Duration.ofMinutes(5).toMillis();

    private final RedissonClient redisson;
    private final Map<String, String> localState = new ConcurrentHashMap<>();
    private final Map<String, String> localResults = new ConcurrentHashMap<>();

    public RedisStepIdempotencyStore(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public BeginResult begin(String taskRunId, String stepId) {
        String compound = compound(taskRunId, stepId);
        String startedValue = startedValue();
        if (redisson == null) {
            String existing = localState.putIfAbsent(compound, startedValue);
            if (isStale(existing) && localState.replace(compound, existing, startedValue)) {
                return BeginResult.ACQUIRED;
            }
            return toResult(existing);
        }
        RMap<String, String> states = redisson.getMap(PREFIX + taskRunId + ":state");
        String existing = states.putIfAbsent(stepId, startedValue);
        if (isStale(existing) && states.replace(stepId, existing, startedValue)) {
            existing = null;
        }
        states.expire(TTL);
        return toResult(existing);
    }

    @Override
    public String loadCommittedResult(String taskRunId, String stepId) {
        if (redisson == null) return localResults.get(compound(taskRunId, stepId));
        return (String) redisson.getMap(PREFIX + taskRunId + ":result").get(stepId);
    }

    @Override
    public void commit(String taskRunId, String stepId, String serializedResult) {
        if (redisson == null) {
            localResults.put(compound(taskRunId, stepId), serializedResult == null ? "null" : serializedResult);
            localState.put(compound(taskRunId, stepId), COMMITTED);
            return;
        }
        RMap<String, String> results = redisson.getMap(PREFIX + taskRunId + ":result");
        results.put(stepId, serializedResult == null ? "null" : serializedResult);
        results.expire(TTL);
        RMap<String, String> states = redisson.getMap(PREFIX + taskRunId + ":state");
        states.put(stepId, COMMITTED);
        states.expire(TTL);
    }

    @Override
    public void release(String taskRunId, String stepId) {
        if (redisson == null) {
            localState.computeIfPresent(compound(taskRunId, stepId),
                    (ignored, state) -> state.startsWith(STARTED) ? null : state);
            return;
        }
        RMap<String, String> states = redisson.getMap(PREFIX + taskRunId + ":state");
        String state = states.get(stepId);
        if (state != null && state.startsWith(STARTED)) states.remove(stepId, state);
    }

    private BeginResult toResult(String existing) {
        if (existing == null) return BeginResult.ACQUIRED;
        return COMMITTED.equals(existing) ? BeginResult.COMMITTED : BeginResult.IN_PROGRESS;
    }

    private String startedValue() {
        return STARTED + System.currentTimeMillis();
    }

    private boolean isStale(String state) {
        if (state == null || !state.startsWith(STARTED)) return false;
        try {
            return System.currentTimeMillis() - Long.parseLong(state.substring(STARTED.length())) > LEASE_MS;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private String compound(String taskRunId, String stepId) {
        return taskRunId + ":" + stepId;
    }
}
