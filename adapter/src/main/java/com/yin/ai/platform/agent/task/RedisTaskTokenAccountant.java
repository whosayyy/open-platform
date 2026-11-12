package com.yin.ai.platform.agent.task;

import org.redisson.api.RedissonClient;

/**
 * @deprecated Redis persistence belongs to the adapter layer. Use
 * {@link com.yin.ai.platform.adapter.runtime.persistence.RedisTaskTokenAccountant}.
 */
@Deprecated(forRemoval = false)
public class RedisTaskTokenAccountant
        extends com.yin.ai.platform.adapter.runtime.persistence.RedisTaskTokenAccountant {

    public RedisTaskTokenAccountant(RedissonClient redisson) {
        super(redisson);
    }
}
