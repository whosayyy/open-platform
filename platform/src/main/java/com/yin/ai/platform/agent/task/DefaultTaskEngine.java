package com.yin.ai.platform.agent.task;

import com.yin.ai.platform.agent.event.EventPublisher;
import org.redisson.api.RedissonClient;

/**
 * @deprecated Redis persistence belongs to the adapter layer. Use
 * {@link com.yin.ai.platform.adapter.runtime.persistence.RedisTaskEngine}.
 */
@Deprecated(forRemoval = false)
public class DefaultTaskEngine
        extends com.yin.ai.platform.adapter.runtime.persistence.RedisTaskEngine {

    public DefaultTaskEngine(RedissonClient redisson, EventPublisher eventPublisher) {
        super(redisson, eventPublisher);
    }
}
