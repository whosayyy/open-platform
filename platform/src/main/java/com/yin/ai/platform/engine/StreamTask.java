package com.yin.ai.platform.engine;

import com.yin.ai.platform.core.runtime.execution.ExecutionEvent;
import reactor.core.publisher.FluxSink;


/**
 * 流式任务。
 */
@FunctionalInterface
public interface StreamTask<T> {
    void execute(FluxSink<ExecutionEvent<T>> sink, ExecutionContext ctx);
}
