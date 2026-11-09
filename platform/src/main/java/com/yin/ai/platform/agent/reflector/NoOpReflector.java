package com.yin.ai.platform.agent.reflector;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.observer.Observation;

/**
 * NoOp 反思器 — D1 占位实现: 成功则完成, 失败则重试一次后完成。
 */
public class NoOpReflector implements Reflector {

    @Override
    public Decision reflect(Observation observation, AgentContext ctx) {
        if (observation.status() == Observation.ObservationStatus.SUCCESS) {
            return Decision.COMPLETE;
        }
        if (ctx.hasExceededRetries()) {
            return Decision.COMPLETE;
        }
        ctx.incrementRetryCount();
        return Decision.RETRY;
    }
}
