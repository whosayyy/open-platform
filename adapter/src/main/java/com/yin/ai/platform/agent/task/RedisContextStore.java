package com.yin.ai.platform.agent.task;

import org.redisson.api.RedissonClient;

import java.util.concurrent.ExecutorService;

/**
 * @deprecated Redis persistence belongs to the adapter layer. Use
 * {@link com.yin.ai.platform.adapter.runtime.persistence.RedisContextStore}.
 */
@Deprecated(forRemoval = false)
public class RedisContextStore
        extends com.yin.ai.platform.adapter.runtime.persistence.RedisContextStore {

    public RedisContextStore(RedissonClient redisson, ExecutorService asyncExecutor) {
        super(redisson, asyncExecutor);
    }
}
