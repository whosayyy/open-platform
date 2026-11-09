package com.yin.ai.platform.memory.store.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.adapter.memory.inmemory.InMemoryMemoryStoreAdapter;
import com.yin.ai.platform.adapter.memory.redis.RedisEpisodicMemoryStore;
import com.yin.ai.platform.adapter.memory.redis.RedisWorkingMemoryStore;
import com.yin.ai.platform.memory.cache.TieredMemoryStore;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * tenant 隔离回归：同 id 不同 ownerKey 的两个 Memory 不得互相读取。
 * 覆盖 InMemory / Tiered（L1 key）/ Redis（key 前缀）三层。
 */
class TenantKeyIsolationTest {

    private static final MemoryId OWNER_A = MemoryId.of("shared-id", MemoryType.EPISODIC, "user-a");
    private static final MemoryId OWNER_B = MemoryId.of("shared-id", MemoryType.EPISODIC, "user-b");

    @Test
    void inMemoryStoreIsolatesSameIdAcrossOwners() {
        InMemoryMemoryStoreAdapter store = new InMemoryMemoryStoreAdapter();
        store.append(OWNER_A, MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "a's content"));

        assertThat(store.readAll(OWNER_A)).hasSize(1);
        assertThat(store.readAll(OWNER_B)).isEmpty();
        assertThat(store.size(OWNER_B)).isZero();

        // 删除 A 不影响 B（B 无数据），反向同样
        store.deleteCell(OWNER_A, MemoryCellId.of("c1"));
        assertThat(store.readAll(OWNER_A)).isEmpty();
    }

    @Test
    void tieredStoreL1KeysAreTenantScoped() {
        TieredMemoryStore store = new TieredMemoryStore(new InMemoryMemoryStoreAdapter());
        store.append(OWNER_A, MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "a's content"));
        store.readTimeline(OWNER_A, 10); // 预热 L1

        assertThat(store.readAll(OWNER_B)).isEmpty();
        // 删除 A 的 cell 不触碰 B（B 无数据即通过）
        assertThat(store.deleteCell(OWNER_A, MemoryCellId.of("c1"))).isTrue();
    }

    @Test
    void redisEpisodicKeyIncludesOwnerPrefix() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        org.redisson.api.RList list = mock(org.redisson.api.RList.class);
        when(list.size()).thenReturn(0);
        when((org.redisson.api.RList) redisson.getList(any(String.class), any())).thenReturn(list);
        RedisEpisodicMemoryStore store = new RedisEpisodicMemoryStore(redisson, new ObjectMapper(), 7200);

        store.size(OWNER_A);
        store.size(OWNER_B);

        // 同一 id 不同 owner 必须落到不同 Redis key
        verify(redisson).getList("ai:memory:episodic:user-a:shared-id", StringCodec.INSTANCE);
        verify(redisson).getList("ai:memory:episodic:user-b:shared-id", StringCodec.INSTANCE);
    }

    @Test
    void redisWorkingStoreKeyIncludesOwnerPrefix() {
        RedissonClient redisson = mock(RedissonClient.class);
        mockBucket(redisson);

        RedisWorkingMemoryStore store = new RedisWorkingMemoryStore(redisson, new ObjectMapper(), 1800);
        store.load(OWNER_A);
        store.load(OWNER_B);

        verify(redisson).getBucket("ai:memory:working:user-a:shared-id", StringCodec.INSTANCE);
        verify(redisson).getBucket("ai:memory:working:user-b:shared-id", StringCodec.INSTANCE);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockBucket(RedissonClient redisson) {
        RBucket bucket = mock(RBucket.class);
        when(redisson.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(bucket);
    }
}
