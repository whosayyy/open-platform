package com.yin.ai.platform.agent.hitl;

import org.redisson.api.RedissonClient;

/**
 * @deprecated Redis persistence belongs to the adapter layer. Use
 * {@link com.yin.ai.platform.adapter.runtime.persistence.RedisHitlStore}.
 */
@Deprecated(forRemoval = false)
public class RedisHitlStore
        extends com.yin.ai.platform.adapter.runtime.persistence.RedisHitlStore {

    public RedisHitlStore(RedissonClient redisson) {
        super(redisson);
    }
}
