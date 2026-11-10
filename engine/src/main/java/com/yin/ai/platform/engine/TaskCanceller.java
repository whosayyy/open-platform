package com.yin.ai.platform.engine;

/**
 * Minimal contract a streaming producer needs in order to cancel a running task.
 * <p>
 * Streaming must not depend on the whole {@code TaskManager}; it only needs the ability to
 * cancel by task id. Keeping this port narrow also removes the circular class dependency
 * between {@code TaskManager} and the stream producer.
 */
@FunctionalInterface
public interface TaskCanceller {

    /** Cancel the task with the given id; unknown ids are ignored. */
    void cancel(String taskId);
}
