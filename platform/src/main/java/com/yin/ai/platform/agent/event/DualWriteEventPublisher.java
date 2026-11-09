package com.yin.ai.platform.agent.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RedissonClient;

/**
 * @deprecated Event persistence belongs to the runtime adapter layer. Use
 * {@link com.yin.ai.platform.adapter.runtime.event.RedisAgentEventPublisher}.
 */
@Deprecated(forRemoval = false)
public class DualWriteEventPublisher
        extends com.yin.ai.platform.adapter.runtime.event.RedisAgentEventPublisher {

    public DualWriteEventPublisher(RedissonClient redisson, ObjectMapper objectMapper) {
        super(redisson, objectMapper);
    }
}
